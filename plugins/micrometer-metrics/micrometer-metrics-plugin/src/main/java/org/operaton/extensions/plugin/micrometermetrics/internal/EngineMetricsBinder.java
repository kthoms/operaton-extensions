/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.impl.metrics.Meter;
import org.operaton.bpm.engine.impl.metrics.MetricsRegistry;
import org.operaton.extensions.plugin.micrometermetrics.MetricNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exposes the engine's built-in database metrics (activity instances, jobs, decisions, ...) as
 * Micrometer function counters, one per metric, without reading the database: each engine meter is
 * replaced by an {@link ObservedMeter} that keeps a never-cleared total next to the engine's own
 * counter, so the engine's reporting to the metrics table is unaffected.
 *
 * <p>Must run after the engine created its meters and before it starts (plugin {@code postInit}).
 */
public final class EngineMetricsBinder {

    private static final Logger LOG = LoggerFactory.getLogger(EngineMetricsBinder.class);

    private EngineMetricsBinder() {}

    /** Micrometer name of an engine metric: {@code activity-instance-start} becomes {@code operaton.engine.activity.instance.start}. */
    public static String meterName(String engineMetricName) {
        return MetricNames.ENGINE_PREFIX + engineMetricName.replace('-', '.');
    }

    /** Installs the observed meters and registers the function counters; returns the bound engine metric names. */
    public static List<String> bind(MetricsRegistry engineRegistry, EngineMeters meters) {
        Map<String, Meter> dbMeters = engineRegistry.getDbMeters();
        List<String> bound = new ArrayList<>();
        for (Map.Entry<String, Meter> entry : dbMeters.entrySet()) {
            ObservedMeter observed =
                    entry.getValue() instanceof ObservedMeter existing ? existing : new ObservedMeter(entry.getValue());
            entry.setValue(observed);
            meters.functionCounter(
                    meterName(entry.getKey()),
                    "Operaton engine metric '" + entry.getKey() + "' since this node started",
                    observed,
                    ObservedMeter::total);
            bound.add(entry.getKey());
        }
        if (bound.isEmpty()) {
            throw new IllegalStateException("The engine's metrics registry holds no meters; "
                    + "the plugin must be initialised after the engine created its metrics");
        }
        LOG.debug("Bound {} engine metrics to Micrometer", bound.size());
        return bound;
    }
}
