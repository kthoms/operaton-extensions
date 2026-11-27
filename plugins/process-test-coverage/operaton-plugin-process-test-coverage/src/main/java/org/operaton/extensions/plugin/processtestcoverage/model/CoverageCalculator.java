/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Computes coverage from recorded events. Stateless. */
public final class CoverageCalculator {

    private CoverageCalculator() {}

    /**
     * Coverage of every model for which {@code events} contains at least one event. An element counts
     * once however often it ran; events for ids the model does not know (for example elements of a
     * non-executable process) are ignored.
     */
    public static CoverageResult calculate(Collection<CoverageEvent> events, Collection<ProcessModel> models) {
        Set<String> touchedKeys = new HashSet<>();
        for (CoverageEvent event : events) {
            touchedKeys.add(event.processKey());
        }
        List<ModelCoverage> result = models.stream()
                .filter(model -> touchedKeys.contains(model.key()))
                .sorted(Comparator.comparing(ProcessModel::key))
                .map(model -> calculate(events, model))
                .toList();
        return new CoverageResult(result);
    }

    /** Coverage of one model; see {@link #calculate(Collection, Collection)}. */
    public static ModelCoverage calculate(Collection<CoverageEvent> events, ProcessModel model) {
        Set<String> nodes = new TreeSet<>();
        Set<String> flows = new TreeSet<>();
        for (CoverageEvent event : events) {
            if (!event.processKey().equals(model.key())) {
                continue;
            }
            switch (event.source()) {
                case FLOW_NODE -> {
                    if (model.flowNodeIds().contains(event.elementId())) {
                        nodes.add(event.elementId());
                    }
                }
                case SEQUENCE_FLOW -> {
                    if (model.sequenceFlowIds().contains(event.elementId())) {
                        flows.add(event.elementId());
                    }
                }
            }
        }
        return new ModelCoverage(model.key(), nodes, flows, nodes.size() + flows.size(), model.totalElementCount());
    }
}
