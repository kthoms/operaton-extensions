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

/**
 * Immutable snapshot of one created incident, captured inside the engine command so that no engine
 * access is needed when the notification is delivered.
 *
 * <p>Derived from the {@code IncidentInformation} class of the ported plugin and extended with the
 * process definition key, business key, tenant and a Cockpit link.
 *
 * @param id incident id
 * @param type incident type, for example {@code failedJob} or {@code failedExternalTask}
 * @param message incident message (truncated to {@value #MAX_MESSAGE_LENGTH} characters)
 * @param activityId id of the activity the incident belongs to, may be {@code null}
 * @param processDefinitionId process definition id
 * @param processDefinitionKey process definition key
 * @param processDefinitionName process definition name, falls back to the key
 * @param processInstanceId process instance id
 * @param executionId execution id, may be {@code null}
 * @param businessKey business key of the process instance, may be {@code null}
 * @param tenantId tenant id, may be {@code null}
 * @param timestamp when the incident was created
 * @param cockpitUrl deep link to the process instance in Cockpit, empty when no base URL is configured
 * @param recipient mail recipients taken from the process variable {@code recipientVariable}, may be {@code null}
 * @param cc mail cc recipients taken from the process variable {@code ccVariable}, may be {@code null}
 */
public record IncidentInfo(
        String id,
        String type,
        String message,
        String activityId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        String processInstanceId,
        String executionId,
        String businessKey,
        String tenantId,
        Instant timestamp,
        String cockpitUrl,
        String recipient,
        String cc) {

    /** Longest incident message kept; stack traces are cut here. */
    public static final int MAX_MESSAGE_LENGTH = 2000;
}
