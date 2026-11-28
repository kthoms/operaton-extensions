/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Embedded engine plus a real Kafka broker: commit publishes, rollback publishes nothing. */
@Testcontainers
@Timeout(120)
class HistoryKafkaIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    private static final ObjectMapper JSON = new ObjectMapper();
    private ProcessEngine engine;
    private KafkaConsumer<String, String> consumer;

    @BeforeAll
    static void waitForBroker() {
        assertThat(KAFKA.isRunning()).isTrue();
    }

    @AfterAll
    static void done() {}

    @AfterEach
    void close() {
        if (consumer != null) {
            consumer.close();
        }
        if (engine != null) {
            engine.close();
        }
    }

    private HistoryKafkaPlugin start(String topicPrefix, SendMode mode) {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers(KAFKA.getBootstrapServers());
        plugin.setTopicPrefix(topicPrefix);
        plugin.setSendMode(mode);
        plugin.setSendTimeoutMillis(15_000);
        plugin.setEventTypes(List.of("process-instance"));
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("it-" + UUID.randomUUID());
        cfg.setJdbcUrl("jdbc:h2:mem:it" + UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .addClasspathResource("bpmn/failing.bpmn")
                .deploy();
        return plugin;
    }

    private List<ConsumerRecord<String, String>> consumeAll(String topic, int atLeast) {
        if (consumer == null) {
            consumer = new KafkaConsumer<>(
                    Map.of(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            KAFKA.getBootstrapServers(),
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "it-" + UUID.randomUUID(),
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest"),
                    new StringDeserializer(),
                    new StringDeserializer());
            consumer.subscribe(List.of(topic));
        }
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> {
            consumer.poll(Duration.ofMillis(300)).forEach(all::add);
            return all.size() >= atLeast;
        });
        return all;
    }

    @Test
    void committedProcessInstanceIsPublishedWithItsIdAsKey() throws Exception {
        String prefix = "it-commit-" + UUID.randomUUID() + ".";
        HistoryKafkaPlugin plugin = start(prefix, SendMode.SYNC);

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        engine.getTaskService()
                .complete(
                        engine.getTaskService().createTaskQuery().singleResult().getId());

        List<ConsumerRecord<String, String>> records = consumeAll(prefix + "process-instance", 2);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(instance.getId());
        assertThat(records)
                .extracting(r -> JSON.readTree(r.value()).get("type").asText())
                .containsExactly("process-instance.start", "process-instance.end");
        assertThat(plugin.getHandler().getPublishedCount()).isEqualTo(2);
    }

    @Test
    void rolledBackTransactionsPublishNothing() throws Exception {
        String prefix = "it-rollback-" + UUID.randomUUID() + ".";
        start(prefix, SendMode.SYNC);

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("failing"))
                .hasMessageContaining("boom");
        assertThatThrownBy(() -> ((org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl)
                                engine.getProcessEngineConfiguration())
                        .getCommandExecutorTxRequired()
                        .execute(ctx -> {
                            engine.getRuntimeService().startProcessInstanceByKey("simple");
                            throw new IllegalStateException("business failure");
                        }))
                .hasMessageContaining("business failure");
        ProcessInstance committed = engine.getRuntimeService().startProcessInstanceByKey("simple");

        // the committed instance is the only one that ever reaches the topic
        List<ConsumerRecord<String, String>> records = consumeAll(prefix + "process-instance", 1);
        Awaitility.await()
                .pollDelay(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .until(() -> {
                    consumer.poll(Duration.ofMillis(300)).forEach(records::add);
                    return true;
                });
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(committed.getId());
        assertThat(records).hasSize(1);
    }

    @Test
    void closingTheEngineClosesTheProducerAndUnreachableBrokerFailsLoudly() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers("localhost:1");
        plugin.setSendMode(SendMode.SYNC);
        plugin.setSendTimeoutMillis(1500);
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("it-" + UUID.randomUUID());
        cfg.setJdbcUrl("jdbc:h2:mem:it" + UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .deploy();

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("simple"))
                .isInstanceOf(HistoryKafkaException.class)
                .hasMessageContaining("after the engine transaction committed");
        assertThat(plugin.getHandler().getFailedCount()).isPositive();
        engine.close();
        engine = null;
        plugin.close(); // idempotent after the engine closed it
    }
}
