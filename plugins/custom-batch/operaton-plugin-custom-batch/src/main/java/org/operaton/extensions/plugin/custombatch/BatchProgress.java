/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

/**
 * Where a batch stands, taken from the engine's batch statistics (or, once the batch is gone, from its
 * history).
 *
 * @param batchId the id of the batch
 * @param type the batch type
 * @param totalJobs how many batch execution jobs the batch has in all
 * @param jobsCreated how many of them the seed job has created so far
 * @param completedJobs how many jobs have run successfully
 * @param remainingJobs how many jobs still have to run, including failed ones and jobs not created yet
 * @param failedJobs how many jobs have no retries left (an incident exists)
 * @param suspended whether the batch is suspended
 * @param finished whether the batch is done: the monitor job saw no job left and removed the batch
 */
public record BatchProgress(
        String batchId,
        String type,
        int totalJobs,
        int jobsCreated,
        int completedJobs,
        int remainingJobs,
        int failedJobs,
        boolean suspended,
        boolean finished) {

    /**
     * The share of jobs that completed, from 0.0 to 1.0; 1.0 for a finished batch, also an empty one.
     *
     * @return the fraction
     */
    public double fractionCompleted() {
        if (finished) {
            return 1.0;
        }
        return totalJobs == 0 ? 0.0 : (double) completedJobs / totalJobs;
    }
}
