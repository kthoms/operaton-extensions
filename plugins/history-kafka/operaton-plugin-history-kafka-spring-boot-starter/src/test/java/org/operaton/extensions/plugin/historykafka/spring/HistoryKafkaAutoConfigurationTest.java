/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.historykafka.HistoryKafkaPlugin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HistoryKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HistoryKafkaAutoConfiguration.class));

    @Test
    void registersThePluginWhenConfigured() {
        runner.withPropertyValues(
                        "operaton.extensions.plugin.history-kafka.bootstrap-servers=localhost:9092",
                        "operaton.extensions.plugin.history-kafka.event-types=process-instance,activity-instance",
                        "operaton.extensions.plugin.history-kafka.topic-mapping.process-instance=pi-topic",
                        "operaton.extensions.plugin.history-kafka.format=cloudevents",
                        "operaton.extensions.plugin.history-kafka.send-timeout=3s")
                .run(context -> {
                    assertThat(context).hasSingleBean(HistoryKafkaPlugin.class);
                    var options = context.getBean(HistoryKafkaPlugin.class).validate();
                    assertThat(options.eventTypes()).containsExactlyInAnyOrder("process-instance", "activity-instance");
                    assertThat(options.topicFor("process-instance", "end")).isEqualTo("pi-topic");
                    assertThat(options.topicFor("activity-instance", "end"))
                            .isEqualTo("operaton.history.activity-instance");
                    assertThat(options.sendTimeout().toSeconds()).isEqualTo(3);
                    assertThat(options.format().name()).isEqualTo("CLOUDEVENTS");
                });
    }

    @Test
    void failsStartupWithoutBootstrapServers() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("bootstrapServers"));
    }

    @Test
    void failsStartupOnUnknownEventType() {
        runner.withPropertyValues(
                        "operaton.extensions.plugin.history-kafka.bootstrap-servers=localhost:9092",
                        "operaton.extensions.plugin.history-kafka.event-types=nonsense")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("operaton.extensions.plugin.history-kafka.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HistoryKafkaPlugin.class));
    }
}
