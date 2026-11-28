/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

class FailurePolicyTest {

    /** A producer whose broker is down: send fails immediately. */
    static final class DownProducer extends MockProducer<String, String> {
        DownProducer(boolean autoComplete) {
            super(
                    autoComplete,
                    (org.apache.kafka.clients.producer.Partitioner) null,
                    new StringSerializer(),
                    new StringSerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(ProducerRecord<String, String> record, Callback callback) {
            throw new KafkaException("broker down");
        }
    }

    private ProcessEngine engine;

    @AfterEach
    void close() {
        if (engine != null) {
            engine.close();
        }
    }

    private HistoryKafkaPlugin start(MockProducer<String, String> producer, FailurePolicy policy, SendMode mode) {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setProducer(producer);
        plugin.setFailurePolicy(policy);
        plugin.setSendMode(mode);
        plugin.setSendTimeoutMillis(300);
        plugin.setEventTypes(java.util.List.of("process-instance"));
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setProcessEngineName("fp-" + java.util.UUID.randomUUID());
        cfg.setJdbcUrl("jdbc:h2:mem:fp" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=1000");
        cfg.setDatabaseSchemaUpdate("true");
        cfg.setJobExecutorActivate(false);
        cfg.setHistory("full");
        cfg.getProcessEnginePlugins().add(plugin);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService()
                .createDeployment()
                .addClasspathResource("bpmn/simple.bpmn")
                .deploy();
        return plugin;
    }

    @Test
    void failPolicyThrowsAfterCommitWithContext() {
        HistoryKafkaPlugin plugin = start(new DownProducer(true), FailurePolicy.FAIL, SendMode.ASYNC);

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("simple"))
                .hasRootCauseMessage("broker down")
                .hasMessageContaining("process-instance.start")
                .hasMessageContaining("NOT rolled back");

        assertThat(plugin.getHandler().getFailedCount()).isEqualTo(1);
        // after commit: the transaction is not rolled back
        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void logAndCountDoesNotThrow() {
        HistoryKafkaPlugin plugin = start(new DownProducer(true), FailurePolicy.LOG_AND_COUNT, SendMode.ASYNC);

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(plugin.getHandler().getFailedCount()).isEqualTo(1);
        assertThat(plugin.getHandler().getPublishedCount()).isZero();
    }

    @Test
    void syncSendTimesOutWhenTheBrokerDoesNotAcknowledge() {
        MockProducer<String, String> silent = new MockProducer<>(
                false,
                (org.apache.kafka.clients.producer.Partitioner) null,
                new StringSerializer(),
                new StringSerializer());
        HistoryKafkaPlugin plugin = start(silent, FailurePolicy.FAIL, SendMode.SYNC);

        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("simple"))
                .isInstanceOf(HistoryKafkaException.class)
                .hasMessageContaining("no acknowledgement from Kafka within 300 ms");
        assertThat(plugin.getHandler().getFailedCount()).isEqualTo(1);
    }

    @Test
    void syncSendCountsAcknowledgedEvents() {
        MockProducer<String, String> producer = new MockProducer<>(
                true,
                (org.apache.kafka.clients.producer.Partitioner) null,
                new StringSerializer(),
                new StringSerializer());
        HistoryKafkaPlugin plugin = start(producer, FailurePolicy.FAIL, SendMode.SYNC);

        engine.getRuntimeService().startProcessInstanceByKey("simple");

        assertThat(plugin.getHandler().getPublishedCount()).isEqualTo(1);
        assertThat(producer.history()).hasSize(1);
    }

    @Test
    void producerSuppliedByTheCallerIsNotClosedWithTheEngine() {
        MockProducer<String, String> producer = new MockProducer<>(
                true,
                (org.apache.kafka.clients.producer.Partitioner) null,
                new StringSerializer(),
                new StringSerializer());
        start(producer, FailurePolicy.FAIL, SendMode.ASYNC);

        engine.close();
        engine = null;

        assertThat(producer.closed()).isFalse();
    }
}
