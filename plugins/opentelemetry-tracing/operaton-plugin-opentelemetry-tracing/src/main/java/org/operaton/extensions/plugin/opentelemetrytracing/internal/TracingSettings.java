/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

/** Validated, immutable settings of one plugin instance. */
public record TracingSettings(
        String traceparentVariable,
        boolean storeTraceparent,
        boolean traceProcessInstances,
        boolean traceActivities,
        boolean traceGateways,
        boolean traceJobs,
        boolean traceExternalTasks,
        boolean includeBusinessKey,
        int maxOpenSpans) {

    public TracingSettings {
        if (storeTraceparent && (traceparentVariable == null || traceparentVariable.isBlank())) {
            throw new IllegalArgumentException(
                    "traceparentVariable must not be blank while storeTraceparent is enabled");
        }
        if (maxOpenSpans < 1) {
            throw new IllegalArgumentException("maxOpenSpans must be at least 1 but was " + maxOpenSpans);
        }
    }
}
