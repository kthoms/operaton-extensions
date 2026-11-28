/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.operaton.extensions.plugin.processtestcoverage.model.CoverageEvent;
import org.operaton.extensions.plugin.processtestcoverage.model.ProcessModel;
import org.operaton.extensions.plugin.processtestcoverage.model.TestRun;
import org.operaton.extensions.plugin.processtestcoverage.model.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receives the events of an engine and attributes them to the active test run.
 *
 * <p>There is one collector per {@link ProcessTestCoveragePlugin}, so one per engine. It is
 * thread-safe: events arrive from any thread that executes a process (test thread, job executor).
 * Events that arrive while no run is active, or for an excluded process definition key, are
 * dropped on purpose: the plugin may stay installed on an engine that is also used outside tests.
 */
public final class CoverageCollector {

    private static final Logger LOG = LoggerFactory.getLogger(CoverageCollector.class);

    private final Map<String, ProcessModel> models = new ConcurrentHashMap<>();
    private final Set<String> seenDefinitionIds = ConcurrentHashMap.newKeySet();
    private final AtomicReference<TestSuite> activeSuite = new AtomicReference<>();
    private final AtomicReference<TestRun> activeRun = new AtomicReference<>();
    private volatile Set<String> excludedProcessKeys = Set.of();

    /** Excludes the given process definition keys: their events are not recorded. */
    public void setExcludedProcessKeys(Collection<String> keys) {
        this.excludedProcessKeys = Set.copyOf(keys);
    }

    /**
     * Starts a suite and forgets the models of an earlier one.
     *
     * @throws IllegalStateException when a suite is already active
     */
    public TestSuite startSuite(String id, String name) {
        TestSuite suite = new TestSuite(id, name);
        if (!activeSuite.compareAndSet(null, suite)) {
            throw new IllegalStateException("cannot start suite '" + id + "': suite '"
                    + activeSuite.get().id() + "' is still active");
        }
        models.clear();
        seenDefinitionIds.clear();
        return suite;
    }

    /**
     * Ends the active suite.
     *
     * @throws IllegalStateException when no suite is active or a run is still active
     */
    public TestSuite endSuite() {
        if (activeRun.get() != null) {
            throw new IllegalStateException(
                    "cannot end the suite: run '" + activeRun.get().id() + "' is still active");
        }
        TestSuite suite = activeSuite.getAndSet(null);
        if (suite == null) {
            throw new IllegalStateException("cannot end the suite: no suite is active");
        }
        return suite;
    }

    /**
     * Starts a run in the active suite; events are attributed to it until {@link #endRun()}.
     *
     * @throws IllegalStateException when no suite is active or a run is already active
     */
    public TestRun startRun(String id, String name, String displayName) {
        TestSuite suite = activeSuite.get();
        if (suite == null) {
            throw new IllegalStateException("cannot start run '" + id + "': no suite is active");
        }
        TestRun run = new TestRun(id, name, displayName);
        if (!activeRun.compareAndSet(null, run)) {
            throw new IllegalStateException(
                    "cannot start run '" + id + "': run '" + activeRun.get().id() + "' is still active");
        }
        suite.addRun(run);
        return run;
    }

    /** Ends the active run, if any; events are dropped until the next run starts. */
    public void endRun() {
        activeRun.set(null);
    }

    /** The active suite, or {@code null}. */
    public TestSuite activeSuite() {
        return activeSuite.get();
    }

    /** The active run, or {@code null}. */
    public TestRun activeRun() {
        return activeRun.get();
    }

    /** The models executed since the active (or last) suite started. */
    public List<ProcessModel> models() {
        return List.copyOf(models.values());
    }

    /**
     * Records an event for the active run.
     *
     * @param event the event
     * @param definitionId the id of the process definition instance that produced it
     * @param modelLoader loads the model of that definition; called at most once per definition id and
     *     suite
     */
    public void record(CoverageEvent event, String definitionId, Supplier<ProcessModel> modelLoader) {
        Objects.requireNonNull(event, "event");
        TestRun run = activeRun.get();
        if (run == null) {
            LOG.debug("dropping {} {}: no active test run", event.source(), event.elementId());
            return;
        }
        if (excludedProcessKeys.contains(event.processKey())) {
            return;
        }
        if (seenDefinitionIds.add(definitionId)) {
            ProcessModel loaded = modelLoader.get();
            models.merge(
                    loaded.key(),
                    loaded,
                    (current, candidate) -> candidate.version() >= current.version() ? candidate : current);
        }
        run.addEvent(event);
    }
}
