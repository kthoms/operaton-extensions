/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.test.Deployment;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.extensions.connector.kafka.producer.DeliveryFailureMode;
import org.operaton.extensions.connector.kafka.producer.KafkaDeliveryException;
import org.operaton.extensions.connector.kafka.producer.OutboundMessage;

/** A broker that cannot be reached: the process stays committed, the failure is reported. */
class AfterCommitFailureTest {

    static final List<String> FAILED_TOPICS = new CopyOnWriteArrayList<>();

    static final KafkaConnectorPlugin PLUGIN = unreachable();

    @RegisterExtension
    static ProcessEngineExtension engine = EngineSupport.engine(PLUGIN);

    @AfterAll
    static void closePlugin() {
        PLUGIN.close();
    }

    private static KafkaConnectorPlugin unreachable() {
        KafkaConnectorPlugin plugin = new KafkaConnectorPlugin();
        plugin.setBootstrapServers("localhost:1");
        plugin.setProducerProperties(Map.of("max.block.ms", "300"));
        plugin.setSendTimeoutMillis(500);
        plugin.setAutoStartConsumers(false);
        plugin.setDeliveryFailureHandler((message, failure) -> FAILED_TOPICS.add(message.topic()));
        return plugin;
    }

    @Test
    @Deployment(resources = "bpmn/produce-connector.bpmn")
    void processIsCommittedAndTheFailureHandlerIsCalled() {
        engine.getRuntimeService()
                .startProcessInstanceByKey(
                        "produce-connector", Variables.putValue("topic", "lost").putValue("payload", "x"));

        assertThat(FAILED_TOPICS).containsExactly("lost");
        assertThat(engine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .finished()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void sendNowReportsTheFailure() {
        assertThatThrownBy(() ->
                        PLUGIN.getSender().sendNow(OutboundMessage.builder("t").build()))
                .isInstanceOf(KafkaDeliveryException.class)
                .hasMessageContaining("topic 't'");
    }

    @Test
    void throwModeThrowsFromTheCommit() {
        assertThat(DeliveryFailureMode.THROW.handler()).isNotNull();
        OutboundMessage message = OutboundMessage.builder("t").build();
        assertThatThrownBy(() ->
                        DeliveryFailureMode.THROW.handler().onFailure(message, new KafkaDeliveryException("x", null)))
                .isInstanceOf(KafkaDeliveryException.class);
    }
}
