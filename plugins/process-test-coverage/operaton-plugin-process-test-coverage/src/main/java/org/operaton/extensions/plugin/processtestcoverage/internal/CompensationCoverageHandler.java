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
import org.operaton.bpm.engine.impl.bpmn.helper.BpmnProperties;
import org.operaton.bpm.engine.impl.event.CompensationEventHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.EventSubscriptionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.extensions.plugin.processtestcoverage.CoverageCollector;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageEvent;
import org.operaton.extensions.plugin.processtestcoverage.model.ElementSource;
import org.operaton.extensions.plugin.processtestcoverage.model.ProcessModel;

/**
 * Compensation boundary events never run as activities: the engine compensates the attached
 * activity directly. This handler records the boundary event when its subscription is triggered.
 */
public final class CompensationCoverageHandler extends CompensationEventHandler {

    private final CoverageCollector collector;

    public CompensationCoverageHandler(CoverageCollector collector) {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    @Override
    public void handleEvent(
            EventSubscriptionEntity eventSubscription,
            Object payload,
            Object localPayload,
            Object payloadToTriggeredScope,
            String businessKey,
            CommandContext commandContext) {
        record(eventSubscription, commandContext);
        super.handleEvent(
                eventSubscription, payload, localPayload, payloadToTriggeredScope, businessKey, commandContext);
    }

    private void record(EventSubscriptionEntity eventSubscription, CommandContext commandContext) {
        ActivityImpl activity = eventSubscription.getActivity();
        ActivityImpl boundaryEvent = activity.getProperties().get(BpmnProperties.COMPENSATION_BOUNDARY_EVENT);
        if (boundaryEvent == null) {
            return;
        }
        ProcessDefinitionEntity definition = (ProcessDefinitionEntity) activity.getProcessDefinition();
        CoverageEvent event = new CoverageEvent(
                ElementSource.FLOW_NODE,
                boundaryEvent.getActivityId(),
                "boundaryEvent",
                definition.getKey(),
                Instant.now());
        collector.record(
                event,
                definition.getId(),
                () -> ProcessModel.load(
                        commandContext.getProcessEngineConfiguration().getRepositoryService(), definition.getId()));
    }
}
