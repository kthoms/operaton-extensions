/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.ProcessEngines;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.management.ProcessDefinitionStatistics;
import org.operaton.extensions.plugin.micrometermetrics.MetricNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the database on a fixed delay and publishes the results as gauges: job backlog, open
 * incidents by type and running process instances per definition key. Gauges read cached values, so
 * a scrape never touches the database. Each poller (jobs, incidents, instances) is switched on
 * separately and fails separately: a failing cycle increments {@code operaton.metrics.poll.errors}
 * and leaves the previous values in place; {@code operaton.metrics.poll.last.success.seconds} shows
 * how stale they are.
 *
 * <p>Thread-safe: values are written by the single polling thread and read by scrapes through atomics.
 */
public final class BacklogPoller implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BacklogPoller.class);

    /** What to poll. */
    public record Settings(
            boolean jobs, boolean incidents, boolean instances, Duration interval, List<String> incidentTypes) {}

    private enum Poller {
        JOBS("jobs"),
        INCIDENTS("incidents"),
        INSTANCES("instances");

        private final String tag;

        Poller(String tag) {
            this.tag = tag;
        }
    }

    private final ProcessEngine engine;
    private final EngineMeters meters;
    private final Settings settings;
    private final CardinalityGuard definitionKeys;
    private final CardinalityGuard incidentTypeGuard;
    private final Runnable onEngineClosed;

    private final AtomicLong jobsDue = new AtomicLong();
    private final AtomicLong jobsFailed = new AtomicLong();
    private final Map<String, AtomicLong> openIncidents = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> runningInstances = new ConcurrentHashMap<>();
    private final Map<Poller, AtomicLong> lastSuccess = new EnumMap<>(Poller.class);
    private final Map<Poller, Counter> errors = new EnumMap<>(Poller.class);

    private final boolean registeredAtStart;
    private ScheduledExecutorService executor;

    public BacklogPoller(
            ProcessEngine engine,
            EngineMeters meters,
            Settings settings,
            CardinalityGuard definitionKeys,
            CardinalityGuard incidentTypeGuard,
            Runnable onEngineClosed) {
        this.engine = engine;
        this.meters = meters;
        this.settings = settings;
        this.definitionKeys = definitionKeys;
        this.incidentTypeGuard = incidentTypeGuard;
        this.onEngineClosed = onEngineClosed;
        this.registeredAtStart = ProcessEngines.getProcessEngines().get(engine.getName()) == engine;
    }

    /** Registers the gauges, runs the first cycle synchronously (failing loudly) and schedules the rest. */
    public synchronized void start() {
        if (executor != null) {
            throw new IllegalStateException("Poller of engine '" + engine.getName() + "' is already started");
        }
        if (settings.jobs()) {
            meters.gauge(MetricNames.JOBS_DUE, "Jobs that are due and executable", jobsDue::get);
            meters.gauge(MetricNames.JOBS_FAILED, "Jobs without retries left", jobsFailed::get);
            registerPollMeters(Poller.JOBS);
        }
        if (settings.incidents()) {
            for (String type : settings.incidentTypes()) {
                incidentGauge(incidentTypeGuard.admit(type));
            }
            incidentGauge(incidentTypeGuard.overflowValue());
            registerPollMeters(Poller.INCIDENTS);
        }
        if (settings.instances()) {
            registerPollMeters(Poller.INSTANCES);
        }
        if (!(settings.jobs() || settings.incidents() || settings.instances())) {
            return;
        }
        pollAll(true);
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "operaton-micrometer-metrics-" + engine.getName());
            thread.setDaemon(true);
            return thread;
        });
        long delay = settings.interval().toMillis();
        executor.scheduleWithFixedDelay(() -> pollAll(false), delay, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void registerPollMeters(Poller poller) {
        AtomicLong success = new AtomicLong();
        lastSuccess.put(poller, success);
        Tag tag = Tag.of(MetricNames.TAG_POLLER, poller.tag);
        meters.gauge(MetricNames.POLL_LAST_SUCCESS, "Epoch seconds of the last successful poll", success::get, tag);
        errors.put(poller, meters.counter(MetricNames.POLL_ERRORS, "Failed poll cycles", tag));
    }

    private void incidentGauge(String type) {
        openIncidents.computeIfAbsent(type, t -> {
            AtomicLong value = new AtomicLong();
            meters.gauge(
                    MetricNames.INCIDENTS_OPEN, "Open incidents", value::get, Tag.of(MetricNames.TAG_INCIDENT_TYPE, t));
            return value;
        });
    }

    private void pollAll(boolean failFast) {
        if (engineClosed()) {
            LOG.debug("Process engine '{}' was closed; stopping its metrics poller", engine.getName());
            close();
            onEngineClosed.run();
            return;
        }
        if (settings.jobs()) {
            run(Poller.JOBS, this::pollJobs, failFast);
        }
        if (settings.incidents()) {
            run(Poller.INCIDENTS, this::pollIncidents, failFast);
        }
        if (settings.instances()) {
            run(Poller.INSTANCES, this::pollInstances, failFast);
        }
    }

    private boolean engineClosed() {
        return registeredAtStart && ProcessEngines.getProcessEngines().get(engine.getName()) != engine;
    }

    private void run(Poller poller, Runnable task, boolean failFast) {
        try {
            task.run();
            lastSuccess.get(poller).set(System.currentTimeMillis() / 1000);
        } catch (RuntimeException e) {
            if (failFast) {
                throw new IllegalStateException(
                        "Initial " + poller.tag + " poll of engine '" + engine.getName() + "' failed", e);
            }
            errors.get(poller).increment();
            LOG.warn(
                    "Polling {} metrics of engine '{}' failed; keeping the previous values",
                    poller.tag,
                    engine.getName(),
                    e);
        }
    }

    private void pollJobs() {
        ManagementService management = engine.getManagementService();
        jobsDue.set(management.createJobQuery().executable().count());
        jobsFailed.set(management.createJobQuery().noRetriesLeft().count());
    }

    private void pollIncidents() {
        RuntimeService runtime = engine.getRuntimeService();
        long total = runtime.createIncidentQuery().count();
        long counted = 0;
        Map<String, Long> perTag = new LinkedHashMap<>();
        for (String type : settings.incidentTypes()) {
            long count = runtime.createIncidentQuery().incidentType(type).count();
            counted += count;
            perTag.merge(incidentTypeGuard.admit(type), count, Long::sum);
        }
        perTag.merge(incidentTypeGuard.overflowValue(), Math.max(0, total - counted), Long::sum);
        perTag.forEach((tag, count) -> openIncidents.get(tag).set(count));
    }

    private void pollInstances() {
        Map<String, Long> perKey = new LinkedHashMap<>();
        for (ProcessDefinitionStatistics statistics : engine.getManagementService()
                .createProcessDefinitionStatisticsQuery()
                .list()) {
            perKey.merge(definitionKeys.admit(statistics.getKey()), (long) statistics.getInstances(), Long::sum);
        }
        for (String key : perKey.keySet()) {
            runningInstances.computeIfAbsent(key, k -> {
                AtomicLong value = new AtomicLong();
                meters.gauge(
                        MetricNames.PROCESS_INSTANCES_RUNNING,
                        "Running process instances",
                        value::get,
                        Tag.of(MetricNames.TAG_PROCESS_DEFINITION_KEY, k));
                return value;
            });
        }
        runningInstances.forEach((key, value) -> value.set(perKey.getOrDefault(key, 0L)));
    }
}
