/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import io.opentelemetry.api.trace.Span;
import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.extensions.plugin.opentelemetrytracing.OperatonTracingAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spans that are open because their process instance or activity instance is still running. Bounded:
 * when the limit is reached the oldest span is ended and flagged. Thread-safe.
 */
final class SpanRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SpanRegistry.class);

    private final int maxOpenSpans;
    private final Map<String, Span> spans = new LinkedHashMap<>();

    SpanRegistry(int maxOpenSpans) {
        this.maxOpenSpans = maxOpenSpans;
    }

    synchronized void put(String key, Span span) {
        spans.remove(key);
        spans.put(key, span);
        if (spans.size() > maxOpenSpans) {
            var oldest = spans.entrySet().iterator().next();
            spans.remove(oldest.getKey());
            LOG.warn(
                    "Open span limit of {} reached, ending the oldest open span '{}' early; raise maxOpenSpans",
                    maxOpenSpans,
                    oldest.getKey());
            oldest.getValue().setAttribute(OperatonTracingAttributes.EVICTED, true);
            oldest.getValue().end();
        }
    }

    /** The open span or {@code null} if there is none; callers handle the absent case explicitly. */
    synchronized Span get(String key) {
        return spans.get(key);
    }

    synchronized Span remove(String key) {
        return spans.remove(key);
    }

    synchronized int size() {
        return spans.size();
    }
}
