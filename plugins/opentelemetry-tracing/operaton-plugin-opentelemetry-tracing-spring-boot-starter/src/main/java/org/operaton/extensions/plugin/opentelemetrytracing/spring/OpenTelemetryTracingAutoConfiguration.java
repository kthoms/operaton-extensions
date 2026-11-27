/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.spring;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.extensions.plugin.opentelemetrytracing.OpenTelemetryTracingPlugin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link OpenTelemetryTracingPlugin} as a process engine plugin. It uses the
 * {@link OpenTelemetry} bean of the application when there is one, else {@link GlobalOpenTelemetry}.
 */
@AutoConfiguration
@ConditionalOnClass({ProcessEnginePlugin.class, OpenTelemetry.class})
@ConditionalOnProperty(
        prefix = "operaton.extensions.plugin.opentelemetry-tracing",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(OpenTelemetryTracingProperties.class)
public class OpenTelemetryTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetryTracingPlugin openTelemetryTracingPlugin(
            OpenTelemetryTracingProperties properties, ObjectProvider<OpenTelemetry> openTelemetry) {
        OpenTelemetryTracingPlugin plugin = new OpenTelemetryTracingPlugin();
        plugin.setOpenTelemetry(openTelemetry.getIfAvailable(GlobalOpenTelemetry::get));
        plugin.setInstrumentationScopeName(properties.getInstrumentationScopeName());
        plugin.setTraceparentVariable(properties.getTraceparentVariable());
        plugin.setStoreTraceparent(properties.isStoreTraceparent());
        plugin.setTraceProcessInstances(properties.isTraceProcessInstances());
        plugin.setTraceActivities(properties.isTraceActivities());
        plugin.setTraceGateways(properties.isTraceGateways());
        plugin.setTraceJobs(properties.isTraceJobs());
        plugin.setTraceExternalTasks(properties.isTraceExternalTasks());
        plugin.setIncludeBusinessKey(properties.isIncludeBusinessKey());
        plugin.setMaxOpenSpans(properties.getMaxOpenSpans());
        return plugin;
    }
}
