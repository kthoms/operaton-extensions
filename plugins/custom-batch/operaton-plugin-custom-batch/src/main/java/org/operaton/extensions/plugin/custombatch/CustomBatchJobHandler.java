/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * class org.camunda.community.batch.CustomBatchJobHandler, adapted to org.operaton.
 */
package org.operaton.extensions.plugin.custombatch;

import java.lang.reflect.Type;
import java.util.List;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;

/**
 * A custom batch whose batch execution jobs process their items together in one transaction.
 *
 * <p>{@link #executeItems} receives at most {@code invocationsPerBatchJob} items. If it throws, the
 * transaction of the job rolls back and the engine retries the job as it does any failed job (default
 * three tries, then an incident); a job that keeps failing keeps <em>all</em> its items, which
 * {@link CustomBatches#failedJobs} reports. Use this class when the work on the items is cheap to
 * repeat or must succeed or fail as a whole. To isolate failing items, extend
 * {@link ItemCustomBatchJobHandler} instead.
 *
 * <pre>{@code
 * public class RecalculateTotalsHandler extends CustomBatchJobHandler<Long> {
 *     public RecalculateTotalsHandler() {
 *         super("recalculate-totals", Long.class);
 *     }
 *
 *     protected void executeItems(List<Long> invoiceIds, CommandContext context) {
 *         // update the invoices; commits with the job
 *     }
 * }
 * }</pre>
 *
 * @param <T> the item type, see {@link AbstractCustomBatchJobHandler}
 */
public abstract class CustomBatchJobHandler<T> extends AbstractCustomBatchJobHandler<T> {

    /**
     * Creates a handler.
     *
     * @param type the batch type, see {@link AbstractCustomBatchJobHandler#AbstractCustomBatchJobHandler}
     * @param itemType the type of one item
     */
    protected CustomBatchJobHandler(String type, Type itemType) {
        super(type, itemType);
    }

    /**
     * Processes the items of one batch execution job, in the transaction of the job.
     *
     * @param items the items of this job, at most {@code invocationsPerBatchJob}; unmodifiable
     * @param commandContext the context of the job's transaction
     */
    protected abstract void executeItems(List<T> items, CommandContext commandContext);

    @Override
    protected final void executeJob(
            CustomBatchConfiguration<T> job, String jobConfigurationId, CommandContext commandContext) {
        executeItems(job.getItems(), commandContext);
    }
}
