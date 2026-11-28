/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.connector.kafka.EngineSupport;
import org.operaton.extensions.connector.kafka.KafkaBroker;
import org.operaton.extensions.connector.kafka.KafkaConnectorPlugin;

/** The inbound side against an embedded engine and a real broker. */
class InboundTest {

    static final KafkaConnectorPlugin PLUGIN = EngineSupport.plugin();

    @RegisterExtension
    static ProcessEngineExtension engine = EngineSupport.engine(PLUGIN);

    private KafkaProcessConsumer consumer;

    @AfterEach
    void stop() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @AfterAll
    static void closePlugin() {
        PLUGIN.close();
    }

    private KafkaProcessConsumer start(InboundSubscription subscription) {
        consumer = KafkaProcessConsumer.create(
                subscription,
                Map.of("bootstrap.servers", KafkaBroker.bootstrapServers()),
                engine.getRuntimeService(),
                PLUGIN.getSender(),
                Duration.ofSeconds(10));
        consumer.start();
        return consumer;
    }

    private InboundSubscription.Builder startOrders(String name, String topic) {
        return InboundSubscription.builder(name)
                .topics(topic)
                .startProcess("wait-task")
                .businessKey("$.orderId")
                .variable("amount", "$.amount")
                .variable("customer", "$.customer.name")
                .variable("trace", "header:traceparent?")
                .variable("key", "key")
                .retryBackoff(Duration.ofMillis(200));
    }

    @Test
    @Deployment(resources = "bpmn/wait-task.bpmn")
    void recordStartsAProcessWithMappedBusinessKeyAndVariables() {
        String topic = KafkaBroker.topic("start");
        InboundSubscription sub = startOrders("start", topic).build();
        start(sub);

        KafkaBroker.send(
                topic,
                "kk",
                "{\"orderId\":\"o-1\",\"amount\":99,\"customer\":{\"name\":\"Ada\"}}",
                Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceBusinessKey("o-1")
                                .count())
                        .isEqualTo(1));
        ProcessInstance instance = engine.getRuntimeService()
                .createProcessInstanceQuery()
                .processInstanceBusinessKey("o-1")
                .singleResult();
        Map<String, Object> vars = engine.getRuntimeService().getVariables(instance.getId());
        assertThat(vars)
                .containsEntry("amount", 99L)
                .containsEntry("customer", "Ada")
                .containsEntry("key", "kk")
                .containsEntry("trace", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(KafkaBroker.committedOffset(sub.groupId(), topic))
                        .isEqualTo(1L));
    }

    @Test
    @Deployment(resources = "bpmn/wait-message.bpmn")
    void recordCorrelatesAMessageByBusinessKeyAndVariable() {
        String topic = KafkaBroker.topic("correlate");
        ProcessInstance target = engine.getRuntimeService()
                .startProcessInstanceByKey("wait-message", "pay-1", Variables.putValue("orderId", "A-9"));
        ProcessInstance other = engine.getRuntimeService()
                .startProcessInstanceByKey("wait-message", "pay-1", Variables.putValue("orderId", "B-9"));
        start(InboundSubscription.builder("correlate")
                .topics(topic)
                .correlateMessage("payment-received")
                .businessKey("key")
                .correlationKey("orderId", "$.orderId")
                .variable("paidAmount", "$.amount")
                .retryBackoff(Duration.ofMillis(200))
                .build());

        KafkaBroker.send(topic, "pay-1", "{\"orderId\":\"A-9\",\"amount\":5}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(engine.getRuntimeService()
                            .createProcessInstanceQuery()
                            .processInstanceId(target.getId())
                            .count())
                    .isZero();
        });
        assertThat(engine.getRuntimeService()
                        .createProcessInstanceQuery()
                        .processInstanceId(other.getId())
                        .count())
                .isEqualTo(1);
        assertThat(engine.getHistoryService()
                        .createHistoricVariableInstanceQuery()
                        .processInstanceId(target.getId())
                        .variableName("paidAmount")
                        .singleResult()
                        .getValue())
                .isEqualTo(5L);
    }

    @Test
    @Deployment(resources = "bpmn/wait-message.bpmn")
    void offsetIsNotCommittedWhileCorrelationFailsAndRecordIsRetried() {
        String topic = KafkaBroker.topic("nocorr");
        InboundSubscription sub = InboundSubscription.builder("nocorr")
                .topics(topic)
                .correlateMessage("payment-received")
                .businessKey("key")
                .retryBackoff(Duration.ofMillis(200))
                .build();
        start(sub);
        KafkaBroker.send(topic, "late-1", "{}");

        // no process waits for the message: the engine call fails again and again
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(consumer.isRunning()).isTrue();
            assertThat(KafkaBroker.committedOffset(sub.groupId(), topic)).isNull();
        });

        ProcessInstance instance = engine.getRuntimeService().startProcessInstanceByKey("wait-message", "late-1");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(engine.getRuntimeService()
                            .createProcessInstanceQuery()
                            .processInstanceId(instance.getId())
                            .count())
                    .isZero();
            assertThat(KafkaBroker.committedOffset(sub.groupId(), topic)).isEqualTo(1L);
        });
    }

    @Test
    @Deployment(resources = "bpmn/wait-task.bpmn")
    void poisonRecordIsDeadLetteredAndTheNextRecordIsProcessed() {
        String topic = KafkaBroker.topic("poison");
        String dlt = KafkaBroker.topic("poison-dlt");
        InboundSubscription sub =
                startOrders("poison", topic).deadLetterTopic(dlt).build();
        start(sub);

        KafkaBroker.send(topic, "bad", "this is not json", Map.of("x-keep", "me"));
        KafkaBroker.send(topic, "good", "{\"orderId\":\"o-2\",\"amount\":1,\"customer\":{\"name\":\"Bob\"}}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceBusinessKey("o-2")
                                .count())
                        .isEqualTo(1));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(KafkaBroker.committedOffset(sub.groupId(), topic))
                        .isEqualTo(2L));
        List<ConsumerRecord<String, String>> dead = KafkaBroker.read(dlt, Duration.ofSeconds(1));
        assertThat(dead).hasSize(1);
        ConsumerRecord<String, String> record = dead.get(0);
        assertThat(record.key()).isEqualTo("bad");
        assertThat(record.value()).isEqualTo("this is not json");
        assertThat(text(record, "x-keep")).isEqualTo("me");
        assertThat(text(record, KafkaProcessConsumer.DLT_REASON)).contains("not valid JSON");
        assertThat(text(record, KafkaProcessConsumer.DLT_ORIGINAL_TOPIC)).isEqualTo(topic);
        assertThat(text(record, KafkaProcessConsumer.DLT_ORIGINAL_OFFSET)).isEqualTo("0");
    }

    @Test
    @Deployment(resources = "bpmn/wait-task.bpmn")
    void poisonRecordCanBeSkipped() {
        String topic = KafkaBroker.topic("skip");
        InboundSubscription sub =
                startOrders("skip", topic).onPoison(PoisonHandling.SKIP).build();
        start(sub);

        KafkaBroker.send(topic, "bad", "{\"amount\":1}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(KafkaBroker.committedOffset(sub.groupId(), topic))
                        .isEqualTo(1L));
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count())
                .isZero();
    }

    @Test
    void recordThatKeepsFailingInTheEngineBecomesPoisonAfterMaxAttempts() {
        String topic = KafkaBroker.topic("maxattempts");
        String dlt = KafkaBroker.topic("maxattempts-dlt");
        InboundSubscription sub = InboundSubscription.builder("maxattempts")
                .topics(topic)
                .startProcess("does-not-exist")
                .deadLetterTopic(dlt)
                .maxAttempts(3)
                .retryBackoff(Duration.ofMillis(100))
                .build();
        start(sub);

        KafkaBroker.send(topic, "k", "{}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(KafkaBroker.committedOffset(sub.groupId(), topic))
                        .isEqualTo(1L));
        List<ConsumerRecord<String, String>> dead = KafkaBroker.read(dlt, Duration.ofSeconds(1));
        assertThat(dead).hasSize(1);
        assertThat(text(dead.get(0), KafkaProcessConsumer.DLT_REASON)).contains("does-not-exist");
    }

    @Test
    @Deployment(resources = "bpmn/wait-task.bpmn")
    void gracefulShutdownCommitsProcessedRecordsAndStopsTheThreads() {
        String topic = KafkaBroker.topic("shutdown");
        InboundSubscription sub = startOrders("shutdown", topic).build();
        start(sub);
        KafkaBroker.send(topic, "k", "{\"orderId\":\"o-3\",\"amount\":1,\"customer\":{\"name\":\"C\"}}");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> engine.getRuntimeService()
                                .createProcessInstanceQuery()
                                .processInstanceBusinessKey("o-3")
                                .count()
                        == 1);

        consumer.close();

        assertThat(consumer.isRunning()).isFalse();
        assertThat(consumer.failures()).isEmpty();
        assertThat(KafkaBroker.committedOffset(sub.groupId(), topic)).isEqualTo(1L);
    }

    private static String text(ConsumerRecord<String, String> record, String header) {
        var h = record.headers().lastHeader(header);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
