/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.example;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The host application owns the OpenTelemetry SDK; the plugin only needs the API. Spans go to an OTLP/HTTP
 * endpoint (Jaeger in the example). The starter picks this bean up.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfiguration {

    @Bean(destroyMethod = "close")
    OpenTelemetrySdk openTelemetry(
            @Value("${example.otel.endpoint:http://localhost:4318}") String endpoint,
            @Value("${example.otel.schedule-delay:PT0.2S}") Duration scheduleDelay) {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint + "/v1/traces")
                .build();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(Resource.getDefault()
                        .merge(Resource.create(io.opentelemetry.api.common.Attributes.of(
                                AttributeKey.stringKey("service.name"), "order-fulfilment"))))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(scheduleDelay)
                        .build())
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }
}
