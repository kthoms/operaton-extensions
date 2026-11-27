/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/** One real Kafka broker (apache/kafka) per test JVM, started on first use, plus helpers to talk to it. */
public final class KafkaBroker {

    private static final KafkaContainer CONTAINER = new KafkaContainer("apache/kafka:3.8.0");

    static {
        CONTAINER.start();
    }

    private KafkaBroker() {}

    public static String bootstrapServers() {
        return CONTAINER.getBootstrapServers();
    }

    /** Creates a topic with one partition and a unique name. */
    public static String topic(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("cannot create topic " + name, e);
        }
        return name;
    }

    public static void send(String topic, String key, String value, Map<String, String> headers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            headers.forEach((name, v) -> record.headers().add(name, v.getBytes(StandardCharsets.UTF_8)));
            producer.send(record).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("cannot send to " + topic, e);
        }
    }

    public static void send(String topic, String key, String value) {
        send(topic, key, value, Map.of());
    }

    /** Reads the topic from the beginning for the given time and returns what is there. */
    public static List<ConsumerRecord<String, String>> read(String topic, Duration wait) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(result::add);
            }
        }
        return result;
    }

    /** The committed offset of the group on partition 0 of the topic, {@code null} when nothing is committed. */
    public static Long committedOffset(String group, String topic) {
        try (AdminClient admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata()
                    .get();
            OffsetAndMetadata committed = offsets.get(new TopicPartition(topic, 0));
            return committed == null ? null : committed.offset();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("cannot read offsets of group " + group, e);
        }
    }

    private static AdminClient admin() {
        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", bootstrapServers());
        return AdminClient.create(config);
    }
}
