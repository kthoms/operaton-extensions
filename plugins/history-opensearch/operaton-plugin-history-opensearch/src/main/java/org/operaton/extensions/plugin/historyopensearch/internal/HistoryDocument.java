/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.time.Instant;
import java.util.Map;

/**
 * The change one history event makes to a current-state document: a partial document (only the fields
 * the event carries) to merge into the document with the given id. Immutable.
 *
 * @param category the history category, see {@link HistoryEventCategories}
 * @param id the document id (process instance id, activity instance id, task id, variable instance id,
 *     incident id)
 * @param fields the fields to merge, values are JSON-compatible
 * @param anchor the instant that decides the document's index when the document is not known yet: the
 *     entity's start or creation time carried by the event, or the current time for a creation event;
 *     {@code null} when the event does not say when the entity began
 * @param eventName the Operaton event name ({@code start}, {@code end}, {@code update}, ...)
 * @param processInstanceId the process instance, for log messages; may be {@code null}
 */
public record HistoryDocument(
        String category,
        String id,
        Map<String, Object> fields,
        Instant anchor,
        String eventName,
        String processInstanceId) {

    /** Context for log and exception messages; never contains field values. */
    public String describe() {
        return "category=" + category + ", id=" + id + ", event=" + eventName + ", processInstanceId="
                + processInstanceId;
    }
}
