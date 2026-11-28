/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.List;

/**
 * Coverage of all process models that were executed by a suite or a run.
 *
 * <p>Only models for which at least one event was recorded take part: a process that is deployed but
 * never started does not lower the coverage.
 *
 * @param models the per-model coverage, in model key order
 */
public record CoverageResult(List<ModelCoverage> models) {

    public CoverageResult {
        models = List.copyOf(models);
    }

    /** Number of covered elements over all models. */
    public int covered() {
        return models.stream().mapToInt(ModelCoverage::covered).sum();
    }

    /** Number of elements over all models. */
    public int total() {
        return models.stream().mapToInt(ModelCoverage::total).sum();
    }

    /** {@code true} when no model was executed, so no ratio exists. */
    public boolean isEmpty() {
        return models.isEmpty();
    }

    /**
     * The overall ratio between 0 and 1.
     *
     * @throws IllegalStateException when no process was executed ({@link #isEmpty()})
     */
    public double ratio() {
        if (models.isEmpty()) {
            throw new IllegalStateException("no process element was executed, the coverage is undefined");
        }
        return (double) covered() / total();
    }
}
