/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.model;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The events recorded while one test method ran. Thread-safe: the job executor adds events from its
 * own threads.
 */
public final class TestRun {

    private final String id;
    private final String name;
    private final String displayName;
    private final Queue<CoverageEvent> events = new ConcurrentLinkedQueue<>();

    public TestRun(String id, String name, String displayName) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
    }

    /** Unique id of the invocation (the JUnit unique id). */
    public String id() {
        return id;
    }

    /** The test method name. */
    public String name() {
        return name;
    }

    /** The display name of the invocation, which differs from the name for parameterized tests. */
    public String displayName() {
        return displayName;
    }

    public void addEvent(CoverageEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    /** A snapshot of the events recorded so far. */
    public List<CoverageEvent> events() {
        return List.copyOf(events);
    }
}
