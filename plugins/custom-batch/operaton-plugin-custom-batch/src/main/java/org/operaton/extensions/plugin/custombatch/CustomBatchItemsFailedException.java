/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import org.operaton.bpm.engine.ProcessEngineException;

/**
 * Thrown by a batch execution job of an {@link ItemCustomBatchJobHandler} after it processed all its
 * items and some of them failed. The job fails (and is retried, then raises an incident) and keeps only
 * the failed items; the cause is the first failure, the others are suppressed exceptions.
 */
public class CustomBatchItemsFailedException extends ProcessEngineException {

    private static final long serialVersionUID = 1L;

    private final int failedItems;
    private final int totalItems;

    /**
     * Creates the exception.
     *
     * @param message the message, listing the first failures
     * @param failedItems how many items of the job failed
     * @param totalItems how many items the job had
     * @param firstFailure the failure of the first failed item
     */
    public CustomBatchItemsFailedException(
            String message, int failedItems, int totalItems, RuntimeException firstFailure) {
        super(message, firstFailure);
        this.failedItems = failedItems;
        this.totalItems = totalItems;
    }

    /** How many items of the job failed. */
    public int getFailedItems() {
        return failedItems;
    }

    /** How many items the job had when it started. */
    public int getTotalItems() {
        return totalItems;
    }
}
