/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.HistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The order process end to end: Kafka in, process on PostgreSQL, Kafka out. */
@SpringBootTest
@Testcontainers
class OrderProcessIT {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("operaton.extensions.connector.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    HistoryService historyService;

    @Test
    void placedOrderIsPaidAndConfirmed() {
        String orderId = "o-" + UUID.randomUUID();
        publish(
                "order-placed",
                orderId,
                "{\"orderId\":\"%s\",\"amount\":250,\"customer\":\"alice\"}".formatted(orderId),
                TRACEPARENT);

        ConsumerRecord<String, String> confirmed = awaitRecord("order-confirmed", orderId);

        assertThat(confirmed.value())
                .contains("\"orderId\":\"%s\"".formatted(orderId))
                .contains("\"status\":\"confirmed\"")
                .contains("\"transactionId\"");
        assertThat(header(confirmed, "operaton-business-key")).isEqualTo(orderId);
        assertThat(header(confirmed, "traceparent")).isEqualTo(TRACEPARENT);
        assertThat(endedAt(orderId)).isEqualTo("End_Confirmed");
    }

    @Test
    void orderAboveTheLimitIsRejected() {
        String orderId = "o-" + UUID.randomUUID();
        publish(
                "order-placed",
                orderId,
                "{\"orderId\":\"%s\",\"amount\":5000,\"customer\":\"bob\"}".formatted(orderId),
                null);

        ConsumerRecord<String, String> rejected = awaitRecord("order-rejected", orderId);

        assertThat(rejected.value()).contains("\"status\":\"rejected\"").doesNotContain("transactionId");
        assertThat(endedAt(orderId)).isEqualTo("End_Rejected");
    }

    @Test
    void recordThatIsNotAnOrderGoesToTheDeadLetterTopic() {
        String key = "bad-" + UUID.randomUUID();
        publish("order-placed", key, "this is not json", null);

        ConsumerRecord<String, String> dead = awaitRecord("order-placed.dlt", key);

        assertThat(dead.value()).isEqualTo("this is not json");
        assertThat(header(dead, "x-dlt-reason")).contains("not valid JSON");
    }

    private String endedAt(String businessKey) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> historyService
                                .createHistoricProcessInstanceQuery()
                                .processInstanceBusinessKey(businessKey)
                                .finished()
                                .count()
                        == 1);
        String instanceId = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey)
                .singleResult()
                .getId();
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(instanceId).list().stream()
                .filter(a -> "noneEndEvent".equals(a.getActivityType()))
                .findFirst()
                .orElseThrow()
                .getActivityId();
    }

    private static void publish(String topic, String key, String value, String traceparent) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException("cannot publish to " + topic, e);
        }
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
