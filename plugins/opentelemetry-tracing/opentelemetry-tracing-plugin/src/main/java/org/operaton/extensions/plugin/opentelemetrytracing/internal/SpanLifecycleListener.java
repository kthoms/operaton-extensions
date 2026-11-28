/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import static org.operaton.extensions.plugin.opentelemetrytracing.OperatonTracingAttributes.*;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import java.time.Instant;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * Starts and ends the span of a process instance or an activity instance. Stateless; the open spans are
 * kept in the {@link Tracing}'s registry, keyed by process or activity instance id.
 */
final class SpanLifecycleListener implements ExecutionListener {

    enum Kind {
        PROCESS,
        ACTIVITY
    }

    private final Tracing tracing;
    private final Kind kind;
    private final String activityType;

    SpanLifecycleListener(Tracing tracing, Kind kind, String activityType) {
        this.tracing = tracing;
        this.kind = kind;
        this.activityType = activityType;
    }

    @Override
    public void notify(DelegateExecution execution) {
        ExecutionEntity e = Tracing.entity(execution);
        if (EVENTNAME_START.equals(execution.getEventName())) {
            start(e);
        } else if (EVENTNAME_END.equals(execution.getEventName())) {
            end(e);
        } else {
            throw new IllegalStateException("Unexpected execution event '" + execution.getEventName() + "'");
        }
    }

    private String key(ExecutionEntity e) {
        return kind == Kind.PROCESS
                ? Tracing.processKey(e.getProcessInstanceId())
                : Tracing.activityKey(e.getActivityInstanceId());
    }

    private void start(ExecutionEntity e) {
        CommandFrame frame = tracing.frames.current();
        String key = key(e);
        Context parent;
        SpanBuilder builder;
        if (kind == Kind.PROCESS) {
            parent = tracing.parentOfProcess(e);
            builder = tracing.builder("process " + e.getProcessDefinition().getKey(), parent);
            tracing.processAttributes(builder, e);
        } else {
            frame.pruneTo(e.getParentActivityInstanceId());
            parent = tracing.parentOfActivity(e.getProcessInstanceId(), e.getParentActivityInstanceId());
            builder = tracing.builder(activityType + " " + e.getActivityId(), parent);
            tracing.activityAttributes(builder, e, activityType);
        }
        Span span = builder.startSpan();
        tracing.registry.put(key, span);
        frame.onRollback(() -> {
            if (tracing.registry.remove(key) != null) {
                span.setAttribute(CANCEL_REASON, "transaction rolled back");
                span.end();
            }
        });
        frame.pushScope(key, false, span.makeCurrent());
        if (kind == Kind.PROCESS
                && tracing.settings.storeTraceparent()
                && span.getSpanContext().isValid()) {
            e.setVariable(tracing.settings.traceparentVariable(), Tracing.encode(span.getSpanContext()));
        }
    }

    private void end(ExecutionEntity e) {
        CommandFrame frame = tracing.frames.current();
        String key = key(e);
        Instant at = Tracing.now();
        String reason = e.getDeleteReason();
        frame.closeDownTo(key);
        Span open = tracing.registry.remove(key);
        if (open != null) {
            frame.onCommit(() -> {
                if (reason != null) {
                    open.setAttribute(CANCEL_REASON, reason);
                }
                open.end(at);
            });
            frame.onRollback(() -> tracing.registry.put(key, open));
            return;
        }
        // not open in this JVM (restart, other cluster node): record the end, flagged
        Context parent = kind == Kind.PROCESS
                ? tracing.processContext(e.getProcessInstanceId())
                : tracing.parentOfActivity(e.getProcessInstanceId(), e.getParentActivityInstanceId());
        String name = kind == Kind.PROCESS
                ? "process " + e.getProcessDefinition().getKey()
                : activityType + " " + e.getActivityId();
        SpanBuilder builder = tracing.builder(name, parent).setStartTimestamp(at);
        if (kind == Kind.PROCESS) {
            tracing.processAttributes(builder, e);
        } else {
            tracing.activityAttributes(builder, e, activityType);
        }
        builder.setAttribute(START_MISSING, true);
        if (reason != null) {
            builder.setAttribute(CANCEL_REASON, reason);
        }
        frame.onCommit(() -> builder.startSpan().end(at));
    }
}
