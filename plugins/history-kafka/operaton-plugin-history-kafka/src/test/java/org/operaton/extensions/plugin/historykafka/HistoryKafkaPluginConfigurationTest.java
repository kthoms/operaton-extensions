/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;

class HistoryKafkaPluginConfigurationTest {

    @Test
    void bootstrapServersAreRequired() {
        assertThatThrownBy(() -> new HistoryKafkaPlugin().validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrapServers");
    }

    @Test
    void bootstrapServersMayComeFromProducerProperties() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setProducerProperties(Map.of("bootstrap.servers", "localhost:9092"));

        assertThat(plugin.validate().format()).isEqualTo(PayloadFormat.JSON);
    }

    @Test
    void serializersCannotBeOverridden() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers("localhost:9092");
        plugin.setProducerProperties(Map.of("value.serializer", "x"));

        assertThatThrownBy(plugin::validate).hasMessageContaining("value.serializer");
    }

    @Test
    void unknownEventTypeAndBadTopicsAreRejected() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers("localhost:9092");
        plugin.setEventTypes(List.of("process-instanze"));
        assertThatThrownBy(plugin::validate).hasMessageContaining("process-instanze");

        plugin.setEventTypes(List.of());
        plugin.setTopic("bad topic");
        assertThatThrownBy(plugin::validate).hasMessageContaining("bad topic");

        plugin.setTopic(null);
        plugin.setSendTimeoutMillis(0);
        assertThatThrownBy(plugin::validate).hasMessageContaining("sendTimeout");
    }

    @Test
    void topicResolutionPrecedence() {
        HistoryKafkaOptions options = new HistoryKafkaOptions(
                "single",
                "p.",
                Map.of("incident", "incidents", "task-instance.create", "new-tasks"),
                List.of().stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()),
                PayloadFormat.JSON,
                "s",
                FailurePolicy.FAIL,
                SendMode.ASYNC,
                java.time.Duration.ofSeconds(1),
                false);

        assertThat(options.topicFor("incident", "create")).isEqualTo("incidents");
        assertThat(options.topicFor("task-instance", "create")).isEqualTo("new-tasks");
        assertThat(options.topicFor("task-instance", "complete")).isEqualTo("single");
        assertThat(HistoryKafkaOptions.defaults().topicFor("job-log", "create")).isEqualTo("operaton.history.job-log");
    }

    @Test
    void explicitHistoryEventHandlerIsRefused() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers("localhost:9092");
        StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
        cfg.setHistoryEventHandler(new org.operaton.bpm.engine.impl.history.handler.DbHistoryEventHandler());

        assertThatThrownBy(() -> plugin.preInit(cfg)).isInstanceOf(HistoryKafkaException.class);
    }

    @Test
    void pluginInstanceServesOneEngine() {
        HistoryKafkaPlugin plugin = new HistoryKafkaPlugin();
        plugin.setBootstrapServers("localhost:9092");
        plugin.preInit(new StandaloneInMemProcessEngineConfiguration());

        assertThatThrownBy(() -> plugin.preInit(new StandaloneInMemProcessEngineConfiguration()))
                .isInstanceOf(IllegalStateException.class);
        plugin.close();
    }
}
