/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.util.Map;
import java.util.Set;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;

/**
 * Names the kind ("category") of a history event, derived from its class. Only the categories that
 * are indexed as current-state documents are known; every other history event is ignored.
 */
public final class HistoryEventCategories {

    /** Category of process instance events. */
    public static final String PROCESS_INSTANCE = "process-instance";
    /** Category of activity instance events. */
    public static final String ACTIVITY_INSTANCE = "activity-instance";
    /** Category of (user) task instance events. */
    public static final String TASK_INSTANCE = "task-instance";
    /** Category of variable instance events. */
    public static final String VARIABLE_INSTANCE = "variable-instance";
    /** Category of incident events. */
    public static final String INCIDENT = "incident";

    private static final Map<String, String> BY_CLASS = Map.of(
            "HistoricProcessInstanceEventEntity", PROCESS_INSTANCE,
            "HistoricActivityInstanceEventEntity", ACTIVITY_INSTANCE,
            "HistoricTaskInstanceEventEntity", TASK_INSTANCE,
            "HistoricVariableUpdateEventEntity", VARIABLE_INSTANCE,
            "HistoricIncidentEventEntity", INCIDENT);

    /** The indexed categories; the valid entries of the {@code eventTypes} filter. */
    public static final Set<String> KNOWN = Set.copyOf(BY_CLASS.values());

    private HistoryEventCategories() {}

    /** The category of the event, or {@code null} when the event is not indexed. */
    public static String of(HistoryEvent event) {
        return BY_CLASS.get(event.getClass().getSimpleName());
    }
}
