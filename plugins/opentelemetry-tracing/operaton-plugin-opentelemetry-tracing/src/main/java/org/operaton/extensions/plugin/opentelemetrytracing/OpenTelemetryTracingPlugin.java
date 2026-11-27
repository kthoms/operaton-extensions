/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.ArrayList;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.plugin.opentelemetrytracing.internal.FrameInterceptor;
import org.operaton.extensions.plugin.opentelemetrytracing.internal.OperationInterceptor;
import org.operaton.extensions.plugin.opentelemetrytracing.internal.Tracing;
import org.operaton.extensions.plugin.opentelemetrytracing.internal.TracingParseListener;
import org.operaton.extensions.plugin.opentelemetrytracing.internal.TracingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates OpenTelemetry spans for process instances, activity instances, job executions and external
 * task API calls, and keeps them in one trace by storing the W3C {@code traceparent} in a process variable.
 *
 * <p>Use one plugin instance per engine. Configure the setters before the engine is built; the plugin is
 * thread-safe once installed. Only the OpenTelemetry API is needed on the class path; the host supplies
 * the {@link OpenTelemetry} instance (default: {@link GlobalOpenTelemetry}).
 */
public class OpenTelemetryTracingPlugin extends AbstractProcessEnginePlugin {

    private static final Logger LOG = LoggerFactory.getLogger(OpenTelemetryTracingPlugin.class);

    private OpenTelemetry openTelemetry;
    private String instrumentationScopeName = "org.operaton.extensions.opentelemetry-tracing";
    private String traceparentVariable = "_traceparent";
    private boolean storeTraceparent = true;
    private boolean traceProcessInstances = true;
    private boolean traceActivities = true;
    private boolean traceGateways = false;
    private boolean traceJobs = true;
    private boolean traceExternalTasks = true;
    private boolean includeBusinessKey = true;
    private int maxOpenSpans = 10_000;

    private volatile Tracing tracing;

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        OpenTelemetry otel = openTelemetry != null ? openTelemetry : GlobalOpenTelemetry.get();
        TracingSettings settings = new TracingSettings(
                traceparentVariable,
                storeTraceparent,
                traceProcessInstances,
                traceActivities,
                traceGateways,
                traceJobs,
                traceExternalTasks,
                includeBusinessKey,
                maxOpenSpans);
        if (instrumentationScopeName == null || instrumentationScopeName.isBlank()) {
            throw new IllegalArgumentException("instrumentationScopeName must not be blank");
        }
        Tracing created = new Tracing(otel, instrumentationScopeName, settings);

        if (configuration.getCustomPostBPMNParseListeners() == null) {
            configuration.setCustomPostBPMNParseListeners(new ArrayList<>());
        }
        configuration.getCustomPostBPMNParseListeners().add(new TracingParseListener(created));

        if (configuration.getCustomPreCommandInterceptorsTxRequired() == null) {
            configuration.setCustomPreCommandInterceptorsTxRequired(new ArrayList<>());
        }
        if (configuration.getCustomPostCommandInterceptorsTxRequired() == null) {
            configuration.setCustomPostCommandInterceptorsTxRequired(new ArrayList<>());
        }
        if (configuration.getCustomPreCommandInterceptorsTxRequiresNew() == null) {
            configuration.setCustomPreCommandInterceptorsTxRequiresNew(new ArrayList<>());
        }
        if (configuration.getCustomPostCommandInterceptorsTxRequiresNew() == null) {
            configuration.setCustomPostCommandInterceptorsTxRequiresNew(new ArrayList<>());
        }
        configuration.getCustomPreCommandInterceptorsTxRequired().add(0, new FrameInterceptor(created, false));
        configuration.getCustomPostCommandInterceptorsTxRequired().add(new OperationInterceptor(created));
        configuration.getCustomPreCommandInterceptorsTxRequiresNew().add(0, new FrameInterceptor(created, true));
        configuration.getCustomPostCommandInterceptorsTxRequiresNew().add(new OperationInterceptor(created));
        this.tracing = created;
        LOG.info(
                "OpenTelemetry tracing installed (traceparent variable: {})",
                storeTraceparent ? traceparentVariable : "off");
    }

    /** Number of spans held open for running process and activity instances; 0 before the engine is built. */
    public int getOpenSpanCount() {
        Tracing t = tracing;
        return t == null ? 0 : t.openSpanCount();
    }

    public void setOpenTelemetry(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    public void setInstrumentationScopeName(String instrumentationScopeName) {
        this.instrumentationScopeName = instrumentationScopeName;
    }

    public void setTraceparentVariable(String traceparentVariable) {
        this.traceparentVariable = traceparentVariable;
    }

    public void setStoreTraceparent(boolean storeTraceparent) {
        this.storeTraceparent = storeTraceparent;
    }

    public void setTraceProcessInstances(boolean traceProcessInstances) {
        this.traceProcessInstances = traceProcessInstances;
    }

    public void setTraceActivities(boolean traceActivities) {
        this.traceActivities = traceActivities;
    }

    public void setTraceGateways(boolean traceGateways) {
        this.traceGateways = traceGateways;
    }

    public void setTraceJobs(boolean traceJobs) {
        this.traceJobs = traceJobs;
    }

    public void setTraceExternalTasks(boolean traceExternalTasks) {
        this.traceExternalTasks = traceExternalTasks;
    }

    public void setIncludeBusinessKey(boolean includeBusinessKey) {
        this.includeBusinessKey = includeBusinessKey;
    }

    public void setMaxOpenSpans(int maxOpenSpans) {
        this.maxOpenSpans = maxOpenSpans;
    }
}
