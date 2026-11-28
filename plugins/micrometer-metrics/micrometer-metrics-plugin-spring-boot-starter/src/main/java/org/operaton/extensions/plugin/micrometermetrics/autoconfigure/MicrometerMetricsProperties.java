/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.operaton.extensions.plugin.micrometermetrics.MicrometerMetricsPlugin;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Properties {@code operaton.extensions.plugin.micrometer-metrics.*}; mapped one to one onto the plugin. */
@ConfigurationProperties(prefix = MicrometerMetricsProperties.PREFIX)
public class MicrometerMetricsProperties {

    public static final String PREFIX = "operaton.extensions.plugin.micrometer-metrics";

    /** Register the plugin at all. */
    private boolean enabled = true;

    /** Expose the engine's built-in metrics as operaton.engine.* counters. */
    private boolean engineMetricsEnabled = true;

    /** Count process instance start/end and incident create/resolve/delete from history events. */
    private boolean historyCountersEnabled = true;

    /** Poll due and failed jobs. */
    private boolean jobBacklogEnabled = true;

    /** Poll open incidents by type. */
    private boolean incidentsEnabled = true;

    /** Poll running process instances per definition key (runs the statistics query; expensive). */
    private boolean runningInstancesEnabled = false;

    /** Time between two poll cycles; whole seconds, at least one second. */
    private Duration pollInterval = Duration.ofSeconds(30);

    /** Maximum distinct process_definition_key tag values; further keys are reported as 'other'. */
    private int maxProcessDefinitionKeys = 100;

    /** Maximum distinct incident_type tag values; further types are reported as 'other'. */
    private int maxIncidentTypes = 20;

    /** Incident types counted individually by operaton.incidents.open; all others are reported as 'other'. */
    private List<String> incidentTypes = new ArrayList<>(MicrometerMetricsPlugin.DEFAULT_INCIDENT_TYPES);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEngineMetricsEnabled() {
        return engineMetricsEnabled;
    }

    public void setEngineMetricsEnabled(boolean engineMetricsEnabled) {
        this.engineMetricsEnabled = engineMetricsEnabled;
    }

    public boolean isHistoryCountersEnabled() {
        return historyCountersEnabled;
    }

    public void setHistoryCountersEnabled(boolean historyCountersEnabled) {
        this.historyCountersEnabled = historyCountersEnabled;
    }

    public boolean isJobBacklogEnabled() {
        return jobBacklogEnabled;
    }

    public void setJobBacklogEnabled(boolean jobBacklogEnabled) {
        this.jobBacklogEnabled = jobBacklogEnabled;
    }

    public boolean isIncidentsEnabled() {
        return incidentsEnabled;
    }

    public void setIncidentsEnabled(boolean incidentsEnabled) {
        this.incidentsEnabled = incidentsEnabled;
    }

    public boolean isRunningInstancesEnabled() {
        return runningInstancesEnabled;
    }

    public void setRunningInstancesEnabled(boolean runningInstancesEnabled) {
        this.runningInstancesEnabled = runningInstancesEnabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getMaxProcessDefinitionKeys() {
        return maxProcessDefinitionKeys;
    }

    public void setMaxProcessDefinitionKeys(int maxProcessDefinitionKeys) {
        this.maxProcessDefinitionKeys = maxProcessDefinitionKeys;
    }

    public int getMaxIncidentTypes() {
        return maxIncidentTypes;
    }

    public void setMaxIncidentTypes(int maxIncidentTypes) {
        this.maxIncidentTypes = maxIncidentTypes;
    }

    public List<String> getIncidentTypes() {
        return incidentTypes;
    }

    public void setIncidentTypes(List<String> incidentTypes) {
        this.incidentTypes = incidentTypes;
    }
}
