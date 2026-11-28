/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

/** What kind of BPMN element a {@link CoverageEvent} refers to. */
public enum ElementSource {
    /** An activity, event or gateway; recorded when the engine starts it. */
    FLOW_NODE,
    /** A sequence flow; recorded when the engine takes it. */
    SEQUENCE_FLOW
}
