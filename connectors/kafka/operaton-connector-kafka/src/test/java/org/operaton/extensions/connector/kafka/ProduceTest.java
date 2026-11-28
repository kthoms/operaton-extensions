/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.connector.kafka.connector.KafkaProduceConnector;
import org.operaton.extensions.connector.kafka.producer.ProduceAction;

/** The outbound side against an embedded engine and a real broker. */
class ProduceTest {

    static final KafkaConnectorPlugin PLUGIN = EngineSupport.plugin();

    @RegisterExtension
    static ProcessEngineExtension engine = EngineSupport.engine(PLUGIN);

    @AfterAll
    static void closePlugin() {
        PLUGIN.close();
    }

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    @Deployment(resources = "bpmn/produce-connector.bpmn")
    void connectorSendsAfterCommitWithProcessHeaders() {
        String topic = KafkaBroker.topic("connector");
        RuntimeService runtime = engine.getRuntimeService();

        ProcessInstance instance = runtime.startProcessInstanceByKey(
                "produce-connector",
                "order-4711",
                Variables.putValue("topic", topic)
                        .putValue("payload", "{\"orderId\":\"order-4711\"}")
                        .putValue("traceparent", TRACEPARENT));

        List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, Duration.ofSeconds(2));
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.key()).isEqualTo("order-4711");
        assertThat(record.value()).isEqualTo("{\"orderId\":\"order-4711\"}");
        assertThat(header(record, ProduceAction.HEADER_PROCESS_INSTANCE_ID)).isEqualTo(instance.getId());
        assertThat(header(record, ProduceAction.HEADER_BUSINESS_KEY)).isEqualTo("order-4711");
        assertThat(header(record, ProduceAction.HEADER_ACTIVITY_ID)).isEqualTo("send");
        assertThat(header(record, ProduceAction.HEADER_PROCESS_DEFINITION_ID)).startsWith("produce-connector:");
        assertThat(header(record, ProduceAction.TRACEPARENT)).isEqualTo(TRACEPARENT);
    }

    @Test
    @Deployment(resources = "bpmn/produce-connector.bpmn")
    void maliciousTraceparentVariableIsNotPropagated() {
        String topic = KafkaBroker.topic("badtrace");

        engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "produce-connector",
                        Variables.putValue("topic", topic)
                                .putValue("payload", "x")
                                .putValue("traceparent", "not-a-traceparent"));

        ConsumerRecord<String, String> record =
                KafkaBroker.read(topic, Duration.ofSeconds(2)).get(0);
        assertThat(record.headers().lastHeader(ProduceAction.TRACEPARENT)).isNull();
    }

    @Test
    @Deployment(resources = "bpmn/produce-then-fail.bpmn")
    void nothingIsProducedWhenTheTransactionRollsBack() {
        String topic = KafkaBroker.topic("rollback");
        RuntimeService runtime = engine.getRuntimeService();

        assertThatThrownBy(() -> runtime.startProcessInstanceByKey(
                        "produce-then-fail", Variables.putValue("topic", topic).putValue("payload", "never")))
                .hasMessageContaining("boom");

        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
        assertThat(KafkaBroker.read(topic, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    @Deployment(resources = "bpmn/produce-delegate.bpmn")
    void delegateSendsAfterCommit() {
        String topic = KafkaBroker.topic("delegate");

        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "produce-delegate",
                        "bk-1",
                        Variables.putValue("topic", topic).putValue("payload", "hello"));

        List<ConsumerRecord<String, String>> records = KafkaBroker.read(topic, Duration.ofSeconds(2));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("bk-1");
        assertThat(records.get(0).value()).isEqualTo("hello");
        assertThat(header(records.get(0), ProduceAction.HEADER_PROCESS_INSTANCE_ID))
                .isEqualTo(instance.getId());
    }

    @Test
    @Deployment(resources = "bpmn/produce-immediate.bpmn")
    void immediateModeReportsPartitionAndOffset() {
        String topic = KafkaBroker.topic("immediate");

        ProcessInstance instance = engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "produce-immediate", Variables.putValue("topic", topic).putValue("payload", "now"));

        assertThat(engine.getHistoryService()
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(instance.getId())
                        .variableName("sentOffset")
                        .singleResult()
                        .getValue())
                .isEqualTo(0L);
        assertThat(KafkaBroker.read(topic, Duration.ofSeconds(1))).hasSize(1);
    }

    @Test
    void connectorWithOwnProducerSendsExplicitHeadersAndSerializesMapsAsJson() {
        String topic = KafkaBroker.topic("adhoc");
        KafkaProduceConnector connector = new KafkaProduceConnector();
        try {
            connector
                    .createRequest()
                    .with("bootstrapServers", KafkaBroker.bootstrapServers())
                    .with("deliveryMode", "IMMEDIATE")
                    .topic(topic)
                    .key("k")
                    .value(Map.of("a", 1))
                    .headers(Map.of("x-custom", "yes", "x-number", 7))
                    .execute();
        } finally {
            connector.close();
        }

        ConsumerRecord<String, String> record =
                KafkaBroker.read(topic, Duration.ofSeconds(1)).get(0);
        assertThat(record.value()).isEqualTo("{\"a\":1}");
        assertThat(header(record, "x-custom")).isEqualTo("yes");
        assertThat(header(record, "x-number")).isEqualTo("7");
        assertThat(record.headers().lastHeader(ProduceAction.HEADER_PROCESS_INSTANCE_ID))
                .isNull();
    }

    @Test
    void connectorRejectsUnknownParametersAndMissingProducer() {
        KafkaProduceConnector connector = new KafkaProduceConnector();

        assertThatThrownBy(() -> connector.createRequest().with("topik", "t").execute())
                .hasMessageContaining("Unknown parameter 'topik'");
        assertThatThrownBy(() -> connector.createRequest().topic("t").execute())
                .hasMessageContaining("No Kafka producer configured");
    }

    @Test
    void afterCommitOutsideAnEngineCommandFails() {
        assertThatThrownBy(() -> PLUGIN.getSender()
                        .sendAfterCommit(org.operaton.extensions.connector.kafka.producer.OutboundMessage.builder("t")
                                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no engine command is active");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
