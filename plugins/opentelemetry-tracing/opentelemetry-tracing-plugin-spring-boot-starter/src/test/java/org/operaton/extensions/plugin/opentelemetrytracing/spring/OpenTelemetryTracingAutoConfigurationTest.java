/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.extensions.plugin.opentelemetrytracing.OpenTelemetryTracingPlugin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class OpenTelemetryTracingAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenTelemetryTracingAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class SdkConfig {
        static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

        @Bean
        OpenTelemetry openTelemetry() {
            return OpenTelemetrySdk.builder()
                    .setTracerProvider(SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(EXPORTER))
                            .build())
                    .build();
        }
    }

    @Test
    void registersThePluginAndUsesTheOpenTelemetryBean() {
        SdkConfig.EXPORTER.reset();
        runner.withUserConfiguration(SdkConfig.class)
                .withPropertyValues("operaton.extensions.plugin.opentelemetry-tracing.trace-gateways=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenTelemetryTracingPlugin.class);
                    StandaloneInMemProcessEngineConfiguration cfg = new StandaloneInMemProcessEngineConfiguration();
                    cfg.setProcessEngineName("autoconfig-test");
                    cfg.setJdbcUrl("jdbc:h2:mem:autoconfig-test;DB_CLOSE_DELAY=1000");
                    cfg.setJobExecutorActivate(false);
                    cfg.getProcessEnginePlugins().add(context.getBean(OpenTelemetryTracingPlugin.class));
                    ProcessEngine engine = cfg.buildProcessEngine();
                    try {
                        engine.getRepositoryService()
                                .createDeployment()
                                .addModelInstance(
                                        "p.bpmn",
                                        Bpmn.createExecutableProcess("auto")
                                                .operatonHistoryTimeToLive(1)
                                                .startEvent()
                                                .exclusiveGateway("gw")
                                                .endEvent()
                                                .done())
                                .deploy();
                        engine.getRuntimeService().startProcessInstanceByKey("auto");
                    } finally {
                        engine.close();
                    }
                    assertThat(SdkConfig.EXPORTER.getFinishedSpanItems())
                            .extracting(s -> s.getName())
                            .contains("process auto", "exclusiveGateway gw");
                });
    }

    @Test
    void worksWithoutAnOpenTelemetryBean() {
        runner.run(context -> assertThat(context).hasSingleBean(OpenTelemetryTracingPlugin.class));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("operaton.extensions.plugin.opentelemetry-tracing.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenTelemetryTracingPlugin.class));
    }
}
