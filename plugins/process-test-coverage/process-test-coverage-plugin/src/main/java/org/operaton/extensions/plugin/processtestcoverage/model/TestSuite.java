/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** All runs of one test class, with the process models they executed. Thread-safe. */
public final class TestSuite {

    private final String id;
    private final String name;
    private final List<TestRun> runs = new CopyOnWriteArrayList<>();

    public TestSuite(String id, String name) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
    }

    /** The test class name. */
    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void addRun(TestRun run) {
        runs.add(Objects.requireNonNull(run, "run"));
    }

    /** Removes a run, for a test that was aborted and therefore tested nothing. */
    public boolean removeRun(String runId) {
        return runs.removeIf(run -> run.id().equals(runId));
    }

    public List<TestRun> runs() {
        return List.copyOf(runs);
    }

    /** The events of all runs. */
    public List<CoverageEvent> events() {
        return runs.stream().flatMap(run -> run.events().stream()).toList();
    }
}
