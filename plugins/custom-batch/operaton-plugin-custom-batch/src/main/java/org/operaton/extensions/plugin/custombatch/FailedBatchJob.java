/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.List;

/**
 * A batch execution job that failed at least once, with the items it still has to process.
 *
 * <p>For a {@link ItemCustomBatchJobHandler} the items are exactly those that failed; for a
 * {@link CustomBatchJobHandler} they are all the items of the job, because its transaction rolled back as
 * a whole.
 *
 * @param jobId the id of the job
 * @param retries the retries left; 0 means the job has an incident
 * @param errorMessage the message of the last failure
 * @param items the items the job still has to process
 * @param <T> the item type
 */
public record FailedBatchJob<T>(String jobId, int retries, String errorMessage, List<T> items) {}
