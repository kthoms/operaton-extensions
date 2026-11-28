/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Reads the history topic on a background thread and feeds {@link LoanAnalytics}. */
@Component
public class LoanHistoryConsumer implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(LoanHistoryConsumer.class);

    private final LoanAnalytics analytics;
    private final String bootstrapServers;
    private final String topic;
    private final ObjectMapper json = new ObjectMapper();
    private volatile KafkaConsumer<String, String> consumer;
    private volatile Thread thread;
    private volatile boolean running;

    public LoanHistoryConsumer(
            LoanAnalytics analytics,
            @Value("${operaton.extensions.plugin.history-kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${operaton.extensions.plugin.history-kafka.topic}") String topic) {
        this.analytics = analytics;
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
    }

    @Override
    public synchronized void start() {
        KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG, "loan-analytics",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                new StringDeserializer());
        kafkaConsumer.subscribe(List.of(topic));
        consumer = kafkaConsumer;
        running = true;
        thread = new Thread(() -> poll(kafkaConsumer), "loan-history-consumer");
        thread.start();
    }

    private void poll(KafkaConsumer<String, String> kafkaConsumer) {
        try {
            while (running) {
                kafkaConsumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    try {
                        analytics.apply(json.readTree(record.value()));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(
                                "Unreadable history event at " + record.topic() + "-" + record.partition() + "@"
                                        + record.offset(),
                                e);
                    }
                });
            }
        } catch (WakeupException expectedOnStop) {
            LOG.debug("Consumer woken up for shutdown");
        } finally {
            kafkaConsumer.close();
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        KafkaConsumer<String, String> kafkaConsumer = consumer;
        if (kafkaConsumer != null) {
            kafkaConsumer.wakeup();
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
