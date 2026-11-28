/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.runtime.Job;

/** Steps through the jobs of a batch by hand, so that tests control every transaction. */
final class Jobs {

    private final ManagementService management;

    Jobs(ManagementService management) {
        this.management = management;
    }

    List<Job> seed(Batch batch) {
        return management
                .createJobQuery()
                .jobDefinitionId(batch.getSeedJobDefinitionId())
                .list();
    }

    List<Job> execution(Batch batch) {
        return management
                .createJobQuery()
                .jobDefinitionId(batch.getBatchJobDefinitionId())
                .list();
    }

    List<Job> monitor(Batch batch) {
        return management
                .createJobQuery()
                .jobDefinitionId(batch.getMonitorJobDefinitionId())
                .list();
    }

    /** Runs the seed job until it has created all jobs. */
    void runSeedToEnd(Batch batch) {
        for (List<Job> seed = seed(batch); !seed.isEmpty(); seed = seed(batch)) {
            management.executeJob(seed.get(0).getId());
        }
    }

    /** Runs every batch execution job once (those without retries left included: they throw). */
    void runExecutionJobs(Batch batch) {
        for (Job job : execution(batch)) {
            management.executeJob(job.getId());
        }
    }

    /** Runs every batch execution job once and returns what the failing ones threw. */
    List<RuntimeException> tryRunExecutionJobs(Batch batch) {
        List<RuntimeException> failures = new ArrayList<>();
        for (Job job : execution(batch)) {
            try {
                management.executeJob(job.getId());
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        return failures;
    }

    /** Runs the monitor job: it removes the batch if no execution job is left. */
    void runMonitor(Batch batch) {
        for (Job job : monitor(batch)) {
            management.executeJob(job.getId());
        }
    }

    /** Runs seed, execution and monitor job in turn until the batch is gone. */
    void runToEnd(Batch batch) {
        runSeedToEnd(batch);
        runExecutionJobs(batch);
        runMonitor(batch);
    }
}
