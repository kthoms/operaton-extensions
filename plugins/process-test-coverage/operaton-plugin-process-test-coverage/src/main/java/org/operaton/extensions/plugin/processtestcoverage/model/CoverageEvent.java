/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One observation of the engine executing a BPMN element.
 *
 * @param source whether the element is a flow node or a sequence flow
 * @param elementId the BPMN id of the element
 * @param elementType the BPMN element type name, for example {@code userTask}
 * @param processKey the key of the process definition the element belongs to
 * @param timestamp when the engine executed the element
 */
public record CoverageEvent(
        ElementSource source, String elementId, String elementType, String processKey, Instant timestamp) {

    public CoverageEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(processKey, "processKey");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
