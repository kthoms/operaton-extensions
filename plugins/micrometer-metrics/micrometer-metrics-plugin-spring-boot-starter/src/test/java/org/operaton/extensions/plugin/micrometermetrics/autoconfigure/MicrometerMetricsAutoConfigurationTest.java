/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.micrometermetrics.MicrometerMetricsPlugin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class MicrometerMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MicrometerMetricsAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void registersThePluginWhenARegistryExists() {
        runner.withUserConfiguration(RegistryConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(MicrometerMetricsPlugin.class));
    }

    @Test
    void backsOffWithoutARegistry() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(MicrometerMetricsPlugin.class));
    }

    @Test
    void canBeDisabled() {
        runner.withUserConfiguration(RegistryConfig.class)
                .withPropertyValues("operaton.extensions.plugin.micrometer-metrics.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MicrometerMetricsPlugin.class));
    }

    @Test
    void bindsProperties() {
        runner.withUserConfiguration(RegistryConfig.class)
                .withPropertyValues(
                        "operaton.extensions.plugin.micrometer-metrics.running-instances-enabled=true",
                        "operaton.extensions.plugin.micrometer-metrics.poll-interval=5s",
                        "operaton.extensions.plugin.micrometer-metrics.incident-types=failedJob,custom")
                .run(ctx -> {
                    MicrometerMetricsProperties props = ctx.getBean(MicrometerMetricsProperties.class);
                    assertThat(props.isRunningInstancesEnabled()).isTrue();
                    assertThat(props.getPollInterval().getSeconds()).isEqualTo(5);
                    assertThat(props.getIncidentTypes()).containsExactly("failedJob", "custom");
                });
    }

    @Test
    void refusesAFractionalPollInterval() {
        runner.withUserConfiguration(RegistryConfig.class)
                .withPropertyValues("operaton.extensions.plugin.micrometer-metrics.poll-interval=500ms")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
