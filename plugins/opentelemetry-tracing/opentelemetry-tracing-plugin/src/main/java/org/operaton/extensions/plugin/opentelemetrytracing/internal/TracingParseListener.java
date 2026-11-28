/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

/** Adds the span start and end listeners to every parsed process and flow node. Stateless after construction. */
public final class TracingParseListener implements BpmnParseListener {

    private final Tracing tracing;

    public TracingParseListener(Tracing tracing) {
        this.tracing = tracing;
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
        if (!tracing.settings.traceProcessInstances()) {
            return;
        }
        SpanLifecycleListener listener =
                new SpanLifecycleListener(tracing, SpanLifecycleListener.Kind.PROCESS, "process");
        processDefinition.addBuiltInListener(ExecutionListener.EVENTNAME_START, listener);
        processDefinition.addListener(ExecutionListener.EVENTNAME_END, listener);
    }

    private void activity(Element element, ActivityImpl activity, boolean gateway) {
        if (!tracing.settings.traceActivities() || (gateway && !tracing.settings.traceGateways())) {
            return;
        }
        Object type = activity.getProperty("type");
        SpanLifecycleListener listener = new SpanLifecycleListener(
                tracing, SpanLifecycleListener.Kind.ACTIVITY, type != null ? type.toString() : element.getTagName());
        activity.addBuiltInListener(ExecutionListener.EVENTNAME_START, listener);
        activity.addListener(ExecutionListener.EVENTNAME_END, listener);
    }

    @Override
    public void parseStartEvent(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseEndEvent(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseExclusiveGateway(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, true);
    }

    @Override
    public void parseInclusiveGateway(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, true);
    }

    @Override
    public void parseParallelGateway(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, true);
    }

    @Override
    public void parseEventBasedGateway(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, true);
    }

    @Override
    public void parseScriptTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseServiceTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseBusinessRuleTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseManualTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseUserTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseSendTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseReceiveTask(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseSubProcess(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseTransaction(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseCallActivity(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseIntermediateThrowEvent(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseIntermediateCatchEvent(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }

    @Override
    public void parseBoundaryEvent(Element e, ScopeImpl s, ActivityImpl a) {
        activity(e, a, false);
    }
}
