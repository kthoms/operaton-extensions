/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import static org.operaton.extensions.plugin.opentelemetrytracing.OperatonTracingAttributes.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.VariableInstanceEntity;

/**
 * Core of the plugin: span creation, parent resolution and trace context propagation through the
 * process instance. One instance per plugin instance; all state is in thread-safe members.
 */
public final class Tracing {

    /** Marks the context of a job or external task span, so activities run by it become its children. */
    record JobTrace(String processInstanceId, Context context) {}

    static final ContextKey<JobTrace> JOB_TRACE = ContextKey.named("operaton.job.trace");

    private static final TextMapSetter<Map<String, String>> SETTER = (carrier, key, value) -> carrier.put(key, value);
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    final TracingSettings settings;
    final Tracer tracer;
    final SpanRegistry registry;
    final CommandFrames frames = new CommandFrames();

    public Tracing(OpenTelemetry openTelemetry, String scopeName, TracingSettings settings) {
        this.settings = settings;
        this.tracer = openTelemetry.getTracer(scopeName);
        this.registry = new SpanRegistry(settings.maxOpenSpans());
    }

    /** Number of spans currently held open for running process and activity instances. */
    public int openSpanCount() {
        return registry.size();
    }

    public TracingSettings settings() {
        return settings;
    }

    // ---- keys -------------------------------------------------------------------------------

    static String processKey(String processInstanceId) {
        return "p:" + processInstanceId;
    }

    static String activityKey(String activityInstanceId) {
        return "a:" + activityInstanceId;
    }

    // ---- trace context propagation ----------------------------------------------------------

    static String encode(SpanContext spanContext) {
        Map<String, String> carrier = new HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.root().with(Span.wrap(spanContext)), carrier, SETTER);
        return carrier.get("traceparent");
    }

    static Context decode(String traceparent) {
        Map<String, String> carrier = Map.of("traceparent", traceparent);
        return W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GETTER);
    }

    /** The context of a process instance's span: the open span, else the stored traceparent, else the current context. */
    Context processContext(String processInstanceId) {
        Span open = registry.get(processKey(processInstanceId));
        if (open != null) {
            return Context.root().with(open);
        }
        if (settings.storeTraceparent()) {
            CommandContext cc = org.operaton.bpm.engine.impl.context.Context.getCommandContext();
            if (cc != null) {
                List<VariableInstanceEntity> vars = cc.getVariableInstanceManager()
                        .findVariableInstancesByExecutionIdAndVariableNames(
                                processInstanceId, List.of(settings.traceparentVariable()));
                for (VariableInstanceEntity v : vars) {
                    String text = v.getTextValue();
                    if (text != null) {
                        Context decoded = decode(text);
                        if (Span.fromContext(decoded).getSpanContext().isValid()) {
                            return decoded;
                        }
                    }
                }
            }
        }
        return Context.current();
    }

    /** Parent for something that happens in an activity instance: its open span, else the process trace. */
    Context activityOrProcessContext(String processInstanceId, String activityInstanceId) {
        if (activityInstanceId != null) {
            Span open = registry.get(activityKey(activityInstanceId));
            if (open != null) {
                return Context.root().with(open);
            }
        }
        return processContext(processInstanceId);
    }

    Context parentOfActivity(String processInstanceId, String parentActivityInstanceId) {
        if (parentActivityInstanceId != null && !parentActivityInstanceId.equals(processInstanceId)) {
            Span scopeSpan = registry.get(activityKey(parentActivityInstanceId));
            if (scopeSpan != null) {
                return Context.root().with(scopeSpan);
            }
        }
        JobTrace job = Context.current().get(JOB_TRACE);
        if (job != null && processInstanceId.equals(job.processInstanceId())) {
            return job.context();
        }
        return processContext(processInstanceId);
    }

    Context parentOfProcess(ExecutionEntity process) {
        ExecutionEntity superExecution = process.getSuperExecution();
        if (superExecution != null) {
            return activityOrProcessContext(
                    superExecution.getProcessInstanceId(), superExecution.getActivityInstanceId());
        }
        return Context.current();
    }

    // ---- span building ----------------------------------------------------------------------

    SpanBuilder builder(String name, Context parent) {
        return tracer.spanBuilder(name).setParent(parent).setSpanKind(SpanKind.INTERNAL);
    }

    static ExecutionEntity entity(DelegateExecution execution) {
        if (execution instanceof ExecutionEntity entity) {
            return entity;
        }
        throw new IllegalStateException(
                "Expected an ExecutionEntity but got " + execution.getClass().getName());
    }

    void processAttributes(SpanBuilder b, ExecutionEntity e) {
        b.setAttribute(PROCESS_INSTANCE_ID, e.getProcessInstanceId());
        b.setAttribute(PROCESS_DEFINITION_ID, e.getProcessDefinitionId());
        b.setAttribute(PROCESS_DEFINITION_KEY, e.getProcessDefinition().getKey());
        if (e.getTenantId() != null) {
            b.setAttribute(TENANT_ID, e.getTenantId());
        }
        String businessKey = e.getProcessBusinessKey();
        if (settings.includeBusinessKey() && businessKey != null) {
            b.setAttribute(BUSINESS_KEY, businessKey);
        }
    }

    void activityAttributes(SpanBuilder b, ExecutionEntity e, String type) {
        processAttributes(b, e);
        b.setAttribute(ACTIVITY_ID, e.getActivityId());
        b.setAttribute(ACTIVITY_TYPE, type);
        b.setAttribute(ACTIVITY_INSTANCE_ID, e.getActivityInstanceId());
        if (e.getCurrentActivityName() != null) {
            b.setAttribute(ACTIVITY_NAME, e.getCurrentActivityName());
        }
    }

    static Instant now() {
        return Instant.now();
    }
}
