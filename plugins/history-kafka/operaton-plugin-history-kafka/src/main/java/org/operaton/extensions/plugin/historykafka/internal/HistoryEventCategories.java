/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;

/**
 * Names the kind ("category") of a history event, derived from its class. The categories are the
 * values accepted by the {@code eventTypes} filter and used in topic names.
 */
public final class HistoryEventCategories {

    private static final Map<String, String> BY_CLASS = Map.ofEntries(
            Map.entry("HistoricProcessInstanceEventEntity", "process-instance"),
            Map.entry("HistoricActivityInstanceEventEntity", "activity-instance"),
            Map.entry("HistoricTaskInstanceEventEntity", "task-instance"),
            Map.entry("HistoricVariableUpdateEventEntity", "variable-instance"),
            Map.entry("HistoricFormPropertyEventEntity", "form-property"),
            Map.entry("HistoricIncidentEventEntity", "incident"),
            Map.entry("HistoricJobLogEvent", "job-log"),
            Map.entry("HistoricExternalTaskLogEntity", "external-task-log"),
            Map.entry("HistoricIdentityLinkLogEventEntity", "identity-link-log"),
            Map.entry("UserOperationLogEntryEventEntity", "user-operation-log"),
            Map.entry("HistoricDecisionInstanceEntity", "decision-instance"),
            Map.entry("HistoricBatchEntity", "batch"),
            Map.entry("HistoricCaseInstanceEventEntity", "case-instance"),
            Map.entry("HistoricCaseActivityInstanceEventEntity", "case-activity-instance"));

    /** Every category the plugin knows; the valid entries of the {@code eventTypes} filter. */
    public static final Set<String> KNOWN = Set.copyOf(BY_CLASS.values());

    private HistoryEventCategories() {}

    /** The category of the event; unknown event classes get a kebab-case name derived from the class. */
    public static String of(HistoryEvent event) {
        String simpleName = event.getClass().getSimpleName();
        String known = BY_CLASS.get(simpleName);
        return known != null ? known : derive(simpleName);
    }

    static String derive(String simpleName) {
        String name = simpleName;
        for (String suffix : List.of("EventEntity", "Entity", "Event")) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        if (name.startsWith("Historic") && name.length() > "Historic".length()) {
            name = name.substring("Historic".length());
        }
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
