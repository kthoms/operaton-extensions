/*
 * Copyright 2020 “J-IT“ IT-Dienstleistungs GesmbH, Vienna, Austria
 * Modifications Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/camunda-community-hub/email-incident-notification-plugin (Apache-2.0)
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.time.Instant;
import java.util.function.Consumer;
import org.operaton.bpm.engine.impl.cfg.TransactionState;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.incident.IncidentContext;
import org.operaton.bpm.engine.impl.incident.IncidentHandler;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.runtime.Incident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates an {@link IncidentHandler}: the wrapped handler keeps creating, resolving and deleting
 * incidents exactly as before (the default {@code failedJob} / {@code failedExternalTask} behaviour
 * or a custom handler), this class only observes the created incident.
 *
 * <p>The notification data is collected inside the engine command and handed over after the
 * transaction committed, so an incident whose transaction rolls back is never announced. Collecting
 * must never affect the transaction: a failure is logged with context and counted.
 *
 * <p>Thread-safe: holds no mutable state.
 */
final class IncidentNotifyingHandler implements IncidentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IncidentNotifyingHandler.class);
    private static final String COCKPIT_PROCESS_INSTANCE_PATH = "/app/cockpit/default/#/process-instance/";

    private final IncidentHandler delegate;
    private final String cockpitBaseUrl;
    private final String recipientVariable;
    private final String ccVariable;
    private final Consumer<IncidentInfo> sink;
    private final NotificationStatistics statistics;

    IncidentNotifyingHandler(
            IncidentHandler delegate,
            String cockpitBaseUrl,
            String recipientVariable,
            String ccVariable,
            Consumer<IncidentInfo> sink,
            NotificationStatistics statistics) {
        this.delegate = delegate;
        this.cockpitBaseUrl = cockpitBaseUrl;
        this.recipientVariable = recipientVariable;
        this.ccVariable = ccVariable;
        this.sink = sink;
        this.statistics = statistics;
    }

    @Override
    public String getIncidentHandlerType() {
        return delegate.getIncidentHandlerType();
    }

    @Override
    public Incident handleIncident(IncidentContext context, String message) {
        Incident incident = delegate.handleIncident(context, message);
        if (incident != null) {
            observe(incident);
        }
        return incident;
    }

    @Override
    public void resolveIncident(IncidentContext context) {
        delegate.resolveIncident(context);
    }

    @Override
    public void deleteIncident(IncidentContext context) {
        delegate.deleteIncident(context);
    }

    private void observe(Incident incident) {
        statistics.observed();
        try {
            IncidentInfo info = collect(incident);
            CommandContext commandContext = Context.getCommandContext();
            if (commandContext == null) {
                sink.accept(info);
            } else {
                commandContext
                        .getTransactionContext()
                        .addTransactionListener(TransactionState.COMMITTED, ctx -> sink.accept(info));
            }
        } catch (RuntimeException e) {
            statistics.collectionFailed();
            // deliberately not rethrown: a notification problem must not roll back the engine command
            LOG.error(
                    "Could not collect the notification data of incident {} (type {}, process instance {}); no notification will be sent",
                    incident.getId(),
                    incident.getIncidentType(),
                    incident.getProcessInstanceId(),
                    e);
        }
    }

    private IncidentInfo collect(Incident incident) {
        String processDefinitionKey = null;
        String processDefinitionName = null;
        if (incident.getProcessDefinitionId() != null) {
            ProcessDefinitionEntity definition = Context.getProcessEngineConfiguration()
                    .getDeploymentCache()
                    .findDeployedProcessDefinitionById(incident.getProcessDefinitionId());
            processDefinitionKey = definition.getKey();
            processDefinitionName = definition.getName();
        }
        String businessKey = null;
        String recipient = null;
        String cc = null;
        if (incident.getExecutionId() != null) {
            ExecutionEntity execution =
                    Context.getCommandContext().getExecutionManager().findExecutionById(incident.getExecutionId());
            if (execution != null) {
                businessKey = execution.getProcessBusinessKey();
                recipient = variable(execution, recipientVariable);
                cc = variable(execution, ccVariable);
            }
        }
        if (processDefinitionKey == null) {
            processDefinitionKey = "unknown";
        }
        String message = incident.getIncidentMessage();
        if (message != null && message.length() > IncidentInfo.MAX_MESSAGE_LENGTH) {
            message = message.substring(0, IncidentInfo.MAX_MESSAGE_LENGTH) + "...";
        }
        return new IncidentInfo(
                incident.getId(),
                incident.getIncidentType(),
                message,
                incident.getActivityId(),
                incident.getProcessDefinitionId(),
                processDefinitionKey,
                processDefinitionName == null ? processDefinitionKey : processDefinitionName,
                incident.getProcessInstanceId(),
                incident.getExecutionId(),
                businessKey,
                incident.getTenantId(),
                incident.getIncidentTimestamp() == null
                        ? Instant.now()
                        : incident.getIncidentTimestamp().toInstant(),
                cockpitUrl(incident.getProcessInstanceId()),
                recipient,
                cc);
    }

    private static String variable(ExecutionEntity execution, String name) {
        Object value = execution.getVariable(name);
        return value == null ? null : value.toString();
    }

    private String cockpitUrl(String processInstanceId) {
        if (cockpitBaseUrl.isEmpty() || processInstanceId == null) {
            return "";
        }
        return cockpitBaseUrl + COCKPIT_PROCESS_INSTANCE_PATH + processInstanceId;
    }
}
