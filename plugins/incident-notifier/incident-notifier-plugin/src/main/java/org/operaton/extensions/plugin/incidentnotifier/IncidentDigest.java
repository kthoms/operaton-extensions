/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.util.List;

/**
 * The unit a {@link NotificationChannel} delivers: the incidents of one process definition collected
 * within one throttle window.
 *
 * @param processDefinitionKey the process definition all incidents belong to
 * @param incidents the incidents to report, oldest first; never empty
 * @param suppressed number of further incidents that were dropped because the buffer was full
 */
public record IncidentDigest(String processDefinitionKey, List<IncidentInfo> incidents, int suppressed) {

    public IncidentDigest {
        if (processDefinitionKey == null || processDefinitionKey.isBlank()) {
            throw new IllegalArgumentException("processDefinitionKey must not be blank");
        }
        if (incidents == null || incidents.isEmpty()) {
            throw new IllegalArgumentException("a digest needs at least one incident");
        }
        if (suppressed < 0) {
            throw new IllegalArgumentException("suppressed must not be negative: " + suppressed);
        }
        incidents = List.copyOf(incidents);
    }

    /** Total number of incidents this digest stands for, including the suppressed ones. */
    public int total() {
        return incidents.size() + suppressed;
    }
}
