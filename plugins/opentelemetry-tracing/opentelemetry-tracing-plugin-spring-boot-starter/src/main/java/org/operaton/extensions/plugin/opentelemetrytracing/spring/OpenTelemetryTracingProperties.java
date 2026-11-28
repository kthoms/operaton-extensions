/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code operaton.extensions.plugin.opentelemetry-tracing.*} onto the plugin's properties. */
@ConfigurationProperties(prefix = "operaton.extensions.plugin.opentelemetry-tracing")
public class OpenTelemetryTracingProperties {

    /** Register the tracing plugin. */
    private boolean enabled = true;

    /** Name of the instrumentation scope of the created spans. */
    private String instrumentationScopeName = "org.operaton.extensions.opentelemetry-tracing";

    /** Name of the process variable that carries the W3C traceparent. */
    private String traceparentVariable = "_traceparent";

    /** Store the traceparent in a process variable so jobs and later commands continue the trace. */
    private boolean storeTraceparent = true;

    /** Create a span per process instance. */
    private boolean traceProcessInstances = true;

    /** Create a span per activity instance. */
    private boolean traceActivities = true;

    /** Include gateways in the activity spans. */
    private boolean traceGateways = false;

    /** Create a span per job execution. */
    private boolean traceJobs = true;

    /** Create spans for the external task fetch, complete, failure, BPMN error and extend-lock commands. */
    private boolean traceExternalTasks = true;

    /** Add the business key as a span attribute. */
    private boolean includeBusinessKey = true;

    /** Upper bound of spans held open for running instances. */
    private int maxOpenSpans = 10_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInstrumentationScopeName() {
        return instrumentationScopeName;
    }

    public void setInstrumentationScopeName(String instrumentationScopeName) {
        this.instrumentationScopeName = instrumentationScopeName;
    }

    public String getTraceparentVariable() {
        return traceparentVariable;
    }

    public void setTraceparentVariable(String traceparentVariable) {
        this.traceparentVariable = traceparentVariable;
    }

    public boolean isStoreTraceparent() {
        return storeTraceparent;
    }

    public void setStoreTraceparent(boolean storeTraceparent) {
        this.storeTraceparent = storeTraceparent;
    }

    public boolean isTraceProcessInstances() {
        return traceProcessInstances;
    }

    public void setTraceProcessInstances(boolean traceProcessInstances) {
        this.traceProcessInstances = traceProcessInstances;
    }

    public boolean isTraceActivities() {
        return traceActivities;
    }

    public void setTraceActivities(boolean traceActivities) {
        this.traceActivities = traceActivities;
    }

    public boolean isTraceGateways() {
        return traceGateways;
    }

    public void setTraceGateways(boolean traceGateways) {
        this.traceGateways = traceGateways;
    }

    public boolean isTraceJobs() {
        return traceJobs;
    }

    public void setTraceJobs(boolean traceJobs) {
        this.traceJobs = traceJobs;
    }

    public boolean isTraceExternalTasks() {
        return traceExternalTasks;
    }

    public void setTraceExternalTasks(boolean traceExternalTasks) {
        this.traceExternalTasks = traceExternalTasks;
    }

    public boolean isIncludeBusinessKey() {
        return includeBusinessKey;
    }

    public void setIncludeBusinessKey(boolean includeBusinessKey) {
        this.includeBusinessKey = includeBusinessKey;
    }

    public int getMaxOpenSpans() {
        return maxOpenSpans;
    }

    public void setMaxOpenSpans(int maxOpenSpans) {
        this.maxOpenSpans = maxOpenSpans;
    }
}
