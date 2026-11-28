/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

class CommitAndRollbackTest {

    static final MockProducer<String, String> PRODUCER = new MockProducer<>(
            true, (org.apache.kafka.clients.producer.Partitioner) null, new StringSerializer(), new StringSerializer());
    static final HistoryKafkaPlugin PLUGIN = plugin();

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> cfg.getProcessEnginePlugins().add(PLUGIN))
            .build();

    static HistoryKafkaPlugin plugin() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setProducer(PRODUCER);
        return plugin;
    }

    @BeforeEach
    void clear() {
        PRODUCER.clear();
    }

    @Test
    @Deployment(resources = "bpmn/simple.bpmn")
    void publishesOnlyAfterCommitAndKeepsTheDatabaseHistory() throws Exception {
        List<Integer> recordsInsideTransaction = new java.util.ArrayList<>();
        ProcessInstance instance = engine.getProcessEngineConfiguration()
                .getCommandExecutorTxRequired()
                .execute(context -> {
                    ProcessInstance pi = engine.getRuntimeService().startProcessInstanceByKey("simple");
                    recordsInsideTransaction.add(PRODUCER.history().size());
                    return pi;
                });

        assertThat(recordsInsideTransaction).containsExactly(0);
        List<ProducerRecord<String, String>> records = PRODUCER.history();
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(r -> assertThat(r.key()).isEqualTo(instance.getId()));
        assertThat(records)
                .extracting(ProducerRecord::topic)
                .contains(
                        "operaton.history.process-instance",
                        "operaton.history.activity-instance",
                        "operaton.history.task-instance");
        ProducerRecord<String, String> first = records.get(0);
        JsonNode json = new ObjectMapper().readTree(first.value());
        assertThat(json.get("type").asText()).isEqualTo("process-instance.start");
        assertThat(json.get("data").get("processInstanceId").asText()).isEqualTo(instance.getId());
        assertThat(json.get("data").get("processDefinitionKey").asText()).isEqualTo("simple");
        assertThat(new String(first.headers().lastHeader("operaton-event-type").value()))
                .isEqualTo("process-instance.start");
        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .count())
                .isEqualTo(1);
        assertThat(PLUGIN.getHandler().getPublishedCount()).isGreaterThanOrEqualTo(records.size());
    }

    @Test
    @Deployment(resources = "bpmn/simple.bpmn")
    void rolledBackTransactionPublishesNothing() {
        long discardedBefore = PLUGIN.getHandler().getDiscardedCount();
        assertThatThrownBy(() -> engine.getProcessEngineConfiguration()
                        .getCommandExecutorTxRequired()
                        .execute(context -> {
                            engine.getRuntimeService().startProcessInstanceByKey("simple");
                            throw new IllegalStateException("business failure");
                        }))
                .hasMessageContaining("business failure");

        assertThat(PRODUCER.history()).isEmpty();
        assertThat(PLUGIN.getHandler().getDiscardedCount()).isGreaterThan(discardedBefore);
        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .count())
                .isZero();
    }

    @Test
    @Deployment(resources = "bpmn/failing.bpmn")
    void failingDelegateRollsBackAndPublishesNothing() {
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("failing"))
                .hasMessageContaining("boom");

        assertThat(PRODUCER.history()).isEmpty();
    }

    @Test
    @Deployment(resources = "bpmn/simple.bpmn")
    void everyCommittedCommandPublishesItsOwnEvents() {
        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("simple");
        PRODUCER.clear();

        engine.getTaskService()
                .complete(
                        engine.getTaskService().createTaskQuery().singleResult().getId());

        assertThat(PRODUCER.history()).extracting(ProducerRecord::key).containsOnly(instance.getId());
        assertThat(PRODUCER.history()).extracting(ProducerRecord::topic).contains("operaton.history.task-instance");
    }
}
