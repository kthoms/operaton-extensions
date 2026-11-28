/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.history.handler.CompositeHistoryEventHandler;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.operaton.extensions.plugin.micrometermetrics.internal.BacklogPoller;
import org.operaton.extensions.plugin.micrometermetrics.internal.CardinalityGuard;
import org.operaton.extensions.plugin.micrometermetrics.internal.EngineMeters;
import org.operaton.extensions.plugin.micrometermetrics.internal.EngineMetricsBinder;
import org.operaton.extensions.plugin.micrometermetrics.internal.HistoryMetricsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that exposes Operaton metrics through Micrometer: the engine's built-in
 * metrics, the job backlog, open incidents, running process instances per definition key, and
 * counters for process instance and incident lifecycle events. See {@link MetricNames} for the
 * meters.
 *
 * <p>Register one instance per process engine; the engine name must be unique per
 * {@link MeterRegistry}. Configure it through the setters before the engine is built; invalid
 * values make the engine refuse to start. The instance is thread-safe once the engine is running;
 * call {@link #close()} to stop polling and remove the meters (it is also done when the engine is
 * closed, with a delay of at most one poll interval).
 */
public class MicrometerMetricsPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerMetricsPlugin.class);

    /** Incident types the engine itself creates. */
    public static final List<String> DEFAULT_INCIDENT_TYPES = List.of("failedJob", "failedExternalTask");

    private MeterRegistry meterRegistry;
    private boolean engineMetricsEnabled = true;
    private boolean historyCountersEnabled = true;
    private boolean jobBacklogEnabled = true;
    private boolean incidentsEnabled = true;
    private boolean runningInstancesEnabled = false;
    private long pollIntervalSeconds = 30;
    private int maxProcessDefinitionKeys = 100;
    private int maxIncidentTypes = 20;
    private List<String> incidentTypes = DEFAULT_INCIDENT_TYPES;

    private ProcessEngineConfigurationImpl configuration;
    private EngineMeters meters;
    private CardinalityGuard definitionKeys;
    private CardinalityGuard incidentTypeGuard;
    private BacklogPoller poller;

    public MicrometerMetricsPlugin() {}

    public MicrometerMetricsPlugin(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ---- lifecycle ------------------------------------------------------------------------

    @Override
    public synchronized void preInit(ProcessEngineConfigurationImpl configuration) {
        if (this.configuration != null && this.configuration != configuration) {
            throw new IllegalStateException(
                    "A MicrometerMetricsPlugin instance serves one process engine; create one per engine");
        }
        validate();
        this.configuration = configuration;
        this.meters = new EngineMeters(meterRegistry, configuration.getProcessEngineName());
        this.definitionKeys = new CardinalityGuard(
                MetricNames.TAG_PROCESS_DEFINITION_KEY, maxProcessDefinitionKeys, MetricNames.OTHER);
        this.incidentTypeGuard =
                new CardinalityGuard(MetricNames.TAG_INCIDENT_TYPE, maxIncidentTypes, MetricNames.OTHER);
    }

    @Override
    public synchronized void postInit(ProcessEngineConfigurationImpl configuration) {
        if (engineMetricsEnabled) {
            if (configuration.getMetricsRegistry() == null) {
                LOG.warn("Engine metrics are disabled in the process engine configuration (metricsEnabled=false); "
                        + "the operaton.engine.* meters are not registered");
            } else {
                EngineMetricsBinder.bind(configuration.getMetricsRegistry(), meters);
            }
        }
        if (historyCountersEnabled) {
            HistoryEventHandler existing = configuration.getHistoryEventHandler();
            HistoryEventHandler counting = new HistoryMetricsHandler(meters, definitionKeys, incidentTypeGuard);
            configuration.setHistoryEventHandler(
                    existing == null ? counting : new CompositeHistoryEventHandler(existing, counting));
            warnIfHistoryLevelHidesEvents(configuration);
        }
    }

    @Override
    public synchronized void postProcessEngineBuild(ProcessEngine engine) {
        BacklogPoller.Settings settings = new BacklogPoller.Settings(
                jobBacklogEnabled,
                incidentsEnabled,
                runningInstancesEnabled,
                Duration.ofSeconds(pollIntervalSeconds),
                incidentTypes);
        poller = new BacklogPoller(engine, meters, settings, definitionKeys, incidentTypeGuard, this::close);
        poller.start();
    }

    /** Stops polling and removes every meter the plugin registered. Idempotent. */
    @Override
    public synchronized void close() {
        if (poller != null) {
            poller.close();
            poller = null;
        }
        if (meters != null) {
            meters.removeAll();
        }
    }

    private void validate() {
        Objects.requireNonNull(
                meterRegistry, "MicrometerMetricsPlugin needs a MeterRegistry (constructor or setMeterRegistry)");
        if (pollIntervalSeconds < 1) {
            throw new IllegalArgumentException("pollIntervalSeconds must be >= 1, was " + pollIntervalSeconds);
        }
        if (maxProcessDefinitionKeys < 1 || maxIncidentTypes < 1) {
            throw new IllegalArgumentException("maxProcessDefinitionKeys and maxIncidentTypes must be >= 1, were "
                    + maxProcessDefinitionKeys + " and " + maxIncidentTypes);
        }
        for (String type : incidentTypes) {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("incidentTypes must not contain blank entries: " + incidentTypes);
            }
        }
    }

    private static void warnIfHistoryLevelHidesEvents(ProcessEngineConfigurationImpl configuration) {
        var level = configuration.getHistoryLevel();
        if (level == null) {
            return;
        }
        if (!level.isHistoryEventProduced(HistoryEventTypes.PROCESS_INSTANCE_START, null)) {
            LOG.warn(
                    "History level '{}' produces no process instance events; the operaton.process.instances.* "
                            + "counters stay at zero (use history level 'activity' or higher)",
                    level.getName());
        }
        if (!level.isHistoryEventProduced(HistoryEventTypes.INCIDENT_CREATE, null)) {
            LOG.warn(
                    "History level '{}' produces no incident events; the operaton.incidents.raised/resolved/deleted "
                            + "counters stay at zero (use history level 'full'; operaton.incidents.open still works)",
                    level.getName());
        }
    }

    // ---- configuration --------------------------------------------------------------------

    /** The registry the meters are registered on. Required. */
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Expose the engine's built-in metrics as {@code operaton.engine.*} counters. Default true. */
    public void setEngineMetricsEnabled(boolean engineMetricsEnabled) {
        this.engineMetricsEnabled = engineMetricsEnabled;
    }

    /** Count process instance start/end and incident create/resolve/delete from history events. Default true. */
    public void setHistoryCountersEnabled(boolean historyCountersEnabled) {
        this.historyCountersEnabled = historyCountersEnabled;
    }

    /** Poll due and failed (no retries) jobs. Default true. */
    public void setJobBacklogEnabled(boolean jobBacklogEnabled) {
        this.jobBacklogEnabled = jobBacklogEnabled;
    }

    /** Poll open incidents by type. Default true. */
    public void setIncidentsEnabled(boolean incidentsEnabled) {
        this.incidentsEnabled = incidentsEnabled;
    }

    /**
     * Poll running process instances per definition key. Default false: it runs the process
     * definition statistics query, which is expensive on large runtime tables.
     */
    public void setRunningInstancesEnabled(boolean runningInstancesEnabled) {
        this.runningInstancesEnabled = runningInstancesEnabled;
    }

    /** Seconds between two poll cycles (>= 1). Default 30. */
    public void setPollIntervalSeconds(long pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    /** Maximum distinct {@code process_definition_key} tag values; further keys become {@code other}. Default 100. */
    public void setMaxProcessDefinitionKeys(int maxProcessDefinitionKeys) {
        this.maxProcessDefinitionKeys = maxProcessDefinitionKeys;
    }

    /** Maximum distinct {@code incident_type} tag values; further types become {@code other}. Default 20. */
    public void setMaxIncidentTypes(int maxIncidentTypes) {
        this.maxIncidentTypes = maxIncidentTypes;
    }

    /**
     * Incident types counted individually by {@code operaton.incidents.open}; every other open incident is
     * reported as {@code other}. Default {@code failedJob}, {@code failedExternalTask}.
     */
    public void setIncidentTypes(List<String> incidentTypes) {
        this.incidentTypes = List.copyOf(new ArrayList<>(Objects.requireNonNull(incidentTypes, "incidentTypes")));
    }
}
