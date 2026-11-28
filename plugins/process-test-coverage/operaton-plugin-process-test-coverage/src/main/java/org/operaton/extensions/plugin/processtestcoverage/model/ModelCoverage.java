/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.Set;

/**
 * Coverage of one process model by a set of events.
 *
 * @param processKey the process definition key
 * @param coveredFlowNodeIds ids of the flow nodes that were executed
 * @param coveredSequenceFlowIds ids of the sequence flows that were taken
 * @param covered number of covered elements (flow nodes plus sequence flows)
 * @param total number of elements of the model
 */
public record ModelCoverage(
        String processKey, Set<String> coveredFlowNodeIds, Set<String> coveredSequenceFlowIds, int covered, int total) {

    /** Covered elements divided by all elements, between 0 and 1. */
    public double ratio() {
        return total == 0 ? 0.0 : (double) covered / total;
    }
}
