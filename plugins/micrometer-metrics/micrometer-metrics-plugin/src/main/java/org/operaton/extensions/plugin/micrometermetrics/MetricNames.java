/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics;

/**
 * Names and tag keys of the meters the plugin registers. Every name starts with {@code operaton.};
 * Prometheus renders dots as underscores and appends {@code _total} to counters.
 */
public final class MetricNames {

    /** Prefix of every meter. */
    public static final String PREFIX = "operaton.";

    /** Prefix of the engine's built-in metrics, e.g. {@code operaton.engine.activity.instance.start}. */
    public static final String ENGINE_PREFIX = PREFIX + "engine.";

    /** Counter: process instances started (history event {@code start}), tags engine, process_definition_key. */
    public static final String PROCESS_INSTANCES_STARTED = PREFIX + "process.instances.started";

    /** Counter: process instances ended, tags engine, process_definition_key, state. */
    public static final String PROCESS_INSTANCES_ENDED = PREFIX + "process.instances.ended";

    /** Gauge: running process instances, tags engine, process_definition_key (polled, off by default). */
    public static final String PROCESS_INSTANCES_RUNNING = PREFIX + "process.instances.running";

    /** Counter: incidents raised (not named 'created': Prometheus reserves that suffix),  tags engine, incident_type, process_definition_key. */
    public static final String INCIDENTS_RAISED = PREFIX + "incidents.raised";

    /** Counter: incidents resolved, tags engine, incident_type, process_definition_key. */
    public static final String INCIDENTS_RESOLVED = PREFIX + "incidents.resolved";

    /** Counter: incidents deleted (for example with their process instance), same tags as raised. */
    public static final String INCIDENTS_DELETED = PREFIX + "incidents.deleted";

    /** Gauge: open incidents, tags engine, incident_type (polled). */
    public static final String INCIDENTS_OPEN = PREFIX + "incidents.open";

    /** Gauge: jobs that are due and executable (retries left, due date reached), tag engine (polled). */
    public static final String JOBS_DUE = PREFIX + "jobs.due";

    /** Gauge: jobs without retries left, tag engine (polled). */
    public static final String JOBS_FAILED = PREFIX + "jobs.failed";

    /** Counter: failed poll cycles, tags engine, poller (jobs, incidents, instances). */
    public static final String POLL_ERRORS = PREFIX + "metrics.poll.errors";

    /** Gauge: epoch seconds of the last successful poll cycle, tags engine, poller. */
    public static final String POLL_LAST_SUCCESS = PREFIX + "metrics.poll.last.success.seconds";

    /** Tag key: the process engine name. */
    public static final String TAG_ENGINE = "engine";

    /** Tag key: the process definition key. */
    public static final String TAG_PROCESS_DEFINITION_KEY = "process_definition_key";

    /** Tag key: the incident type, e.g. {@code failedJob}. */
    public static final String TAG_INCIDENT_TYPE = "incident_type";

    /** Tag key: how a process instance ended ({@code completed}, {@code externally_terminated}, ...). */
    public static final String TAG_STATE = "state";

    /** Tag key: the poller a poll meter belongs to. */
    public static final String TAG_POLLER = "poller";

    /** Tag value that collects every value beyond the cardinality limit. */
    public static final String OTHER = "other";

    private MetricNames() {}
}
