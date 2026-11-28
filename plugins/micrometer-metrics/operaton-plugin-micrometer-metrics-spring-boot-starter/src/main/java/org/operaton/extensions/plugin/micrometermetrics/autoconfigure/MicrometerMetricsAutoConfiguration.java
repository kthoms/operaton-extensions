/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.extensions.plugin.micrometermetrics.MicrometerMetricsPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link MicrometerMetricsPlugin} as a process engine plugin bean (Operaton's Spring
 * Boot starter adds every {@link ProcessEnginePlugin} bean to the engine) when a {@link MeterRegistry}
 * bean exists. Ordered after the Micrometer registry auto-configurations of Spring Boot 3 and 4.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnClass({ProcessEnginePlugin.class, MeterRegistry.class})
@ConditionalOnProperty(prefix = MicrometerMetricsProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MicrometerMetricsProperties.class)
public class MicrometerMetricsAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public MicrometerMetricsPlugin micrometerMetricsPlugin(
            MeterRegistry registry, MicrometerMetricsProperties properties) {
        Duration interval = properties.getPollInterval();
        if (interval == null || interval.isNegative() || interval.getNano() != 0 || interval.getSeconds() < 1) {
            throw new IllegalArgumentException(MicrometerMetricsProperties.PREFIX
                    + ".poll-interval must be a whole number of seconds, at least 1s, but was " + interval);
        }
        MicrometerMetricsPlugin plugin = new MicrometerMetricsPlugin(registry);
        plugin.setEngineMetricsEnabled(properties.isEngineMetricsEnabled());
        plugin.setHistoryCountersEnabled(properties.isHistoryCountersEnabled());
        plugin.setJobBacklogEnabled(properties.isJobBacklogEnabled());
        plugin.setIncidentsEnabled(properties.isIncidentsEnabled());
        plugin.setRunningInstancesEnabled(properties.isRunningInstancesEnabled());
        plugin.setPollIntervalSeconds(interval.getSeconds());
        plugin.setMaxProcessDefinitionKeys(properties.getMaxProcessDefinitionKeys());
        plugin.setMaxIncidentTypes(properties.getMaxIncidentTypes());
        plugin.setIncidentTypes(properties.getIncidentTypes());
        return plugin;
    }
}
