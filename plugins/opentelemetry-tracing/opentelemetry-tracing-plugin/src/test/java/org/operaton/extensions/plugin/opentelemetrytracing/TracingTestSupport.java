/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

/** An OpenTelemetry SDK with an in-memory exporter, and helpers shared by the tests. */
final class TracingTestSupport implements AutoCloseable {

    final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    final OpenTelemetrySdk sdk =
            OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

    Tracer tracer() {
        return sdk.getTracer("test");
    }

    List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    SpanData single(String name) {
        List<SpanData> found =
                spans().stream().filter(s -> s.getName().equals(name)).toList();
        if (found.size() != 1) {
            throw new AssertionError("expected one span named '" + name + "' but found " + found.size() + " in "
                    + spans().stream().map(SpanData::getName).toList());
        }
        return found.get(0);
    }

    static Deployment deploy(ProcessEngine engine, BpmnModelInstance model) {
        return engine.getRepositoryService()
                .createDeployment()
                .addModelInstance("model.bpmn", model)
                .deploy();
    }

    static String spanId(Span span) {
        return span.getSpanContext().getSpanId();
    }

    @Override
    public void close() {
        sdk.close();
    }

    /** A delegate that does nothing. */
    public static class Noop implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {}
    }

    /** A delegate that fails. */
    public static class Fail implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            throw new IllegalStateException("boom");
        }
    }
}
