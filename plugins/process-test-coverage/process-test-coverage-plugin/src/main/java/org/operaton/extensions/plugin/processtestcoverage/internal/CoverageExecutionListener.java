/*
 * Copyright (C) 2019 - 2024 Camunda
 * Modifications Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/camunda-process-test-coverage (Apache-2.0),
 * translated from Kotlin to Java and adapted to Operaton.
 */
package org.operaton.extensions.plugin.processtestcoverage.internal;

import java.time.Instant;
import java.util.Objects;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.EventBasedGateway;
import org.operaton.bpm.model.bpmn.instance.EventDefinition;
import org.operaton.bpm.model.bpmn.instance.FlowNode;
import org.operaton.bpm.model.bpmn.instance.IntermediateCatchEvent;
import org.operaton.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.operaton.bpm.model.bpmn.instance.LinkEventDefinition;
import org.operaton.bpm.model.bpmn.instance.SequenceFlow;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.operaton.extensions.plugin.processtestcoverage.CoverageCollector;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageEvent;
import org.operaton.extensions.plugin.processtestcoverage.model.ElementSource;
import org.operaton.extensions.plugin.processtestcoverage.model.ProcessModel;

/**
 * Engine listener attached to every activity ({@code start}) and sequence flow ({@code take}) of
 * every parsed process. Stateless apart from the thread-safe collector, so one instance serves all
 * definitions and threads.
 *
 * <p>The engine does not take the sequence flow behind an event-based gateway (only intermediate
 * catch events may follow it) and does not run
 * link throw events; both are derived from the model when the following catch event starts.
 */
final class CoverageExecutionListener implements ExecutionListener {

    private final CoverageCollector collector;

    CoverageExecutionListener(CoverageCollector collector) {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    @Override
    public void notify(DelegateExecution execution) {
        String eventName = execution.getEventName();
        if (EVENTNAME_START.equals(eventName)) {
            onStart(execution);
        } else if (EVENTNAME_TAKE.equals(eventName)) {
            record(
                    execution,
                    ElementSource.SEQUENCE_FLOW,
                    execution.getCurrentTransitionId(),
                    execution.getBpmnModelElementInstance());
        } else {
            throw new IllegalStateException("process test coverage listener received unexpected event '" + eventName
                    + "' for execution " + execution.getId());
        }
    }

    private void onStart(DelegateExecution execution) {
        ModelElementInstance element = execution.getBpmnModelElementInstance();
        if (element instanceof IntermediateCatchEvent catchEvent) {
            recordFlowsFromEventBasedGateways(execution, catchEvent);
            recordLinkThrowEvents(execution, catchEvent);
        }
        record(execution, ElementSource.FLOW_NODE, execution.getCurrentActivityId(), element);
    }

    private void recordFlowsFromEventBasedGateways(DelegateExecution execution, FlowNode node) {
        // sourceRef/targetRef are authoritative; the optional <incoming> children are not always present
        for (SequenceFlow flow : execution.getBpmnModelInstance().getModelElementsByType(SequenceFlow.class)) {
            if (flow.getTarget().equals(node) && flow.getSource() instanceof EventBasedGateway) {
                record(execution, ElementSource.SEQUENCE_FLOW, flow.getId(), flow);
            }
        }
    }

    private void recordLinkThrowEvents(DelegateExecution execution, IntermediateCatchEvent catchEvent) {
        BpmnModelInstance model = execution.getBpmnModelInstance();
        for (EventDefinition definition : catchEvent.getEventDefinitions()) {
            if (!(definition instanceof LinkEventDefinition catchLink)) {
                continue;
            }
            for (IntermediateThrowEvent throwEvent : model.getModelElementsByType(IntermediateThrowEvent.class)) {
                boolean sameScope = catchEvent.getParentElement().equals(throwEvent.getParentElement());
                boolean sameLink = throwEvent.getEventDefinitions().stream()
                        .anyMatch(candidate -> candidate instanceof LinkEventDefinition throwLink
                                && catchLink.getName() != null
                                && catchLink.getName().equals(throwLink.getName()));
                if (sameScope && sameLink) {
                    record(execution, ElementSource.FLOW_NODE, throwEvent.getId(), throwEvent);
                }
            }
        }
    }

    private void record(
            DelegateExecution execution, ElementSource source, String elementId, ModelElementInstance element) {
        if (!(execution instanceof ExecutionEntity entity)) {
            throw new IllegalStateException("process test coverage needs an ExecutionEntity but got "
                    + execution.getClass().getName());
        }
        String definitionId = execution.getProcessDefinitionId();
        String processKey = entity.getProcessDefinition().getKey();
        String elementType =
                element == null ? "unknown" : element.getElementType().getTypeName();
        CoverageEvent event = new CoverageEvent(source, elementId, elementType, processKey, Instant.now());
        collector.record(
                event,
                definitionId,
                () -> ProcessModel.load(execution.getProcessEngineServices().getRepositoryService(), definitionId));
    }
}
