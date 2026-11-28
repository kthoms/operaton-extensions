/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * class org.camunda.community.batch.spring.FailsafeCustomBatchJobHandler (one new transaction per item),
 * rebuilt on the engine's own requires-new command executor so that it works without Spring.
 */
package org.operaton.extensions.plugin.custombatch;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor;
import org.operaton.bpm.engine.impl.persistence.entity.ByteArrayEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A custom batch whose items are processed one by one, each in its own transaction, so that a failing
 * item does not undo the others.
 *
 * <p>For every item {@link #executeItem} runs in a new transaction (the engine's requires-new command
 * executor; with Spring it is a new Spring transaction). Items that fail are collected and reported
 * through {@link #onItemFailure}; the processing goes on. When all items were tried and some failed,
 * the job commits a copy of its configuration that holds <em>only the failed items</em> and then fails
 * with a {@link CustomBatchItemsFailedException}. The engine retries the job, which now processes just
 * those items; when the retries are used up an incident is raised and {@link CustomBatches#failedJobs}
 * reports exactly the items that failed. Items that succeeded are never run again.
 *
 * <p>Needs a second database connection while the job holds its own: size the connection pool for twice
 * the job executor's maximum pool size.
 *
 * @param <T> the item type, see {@link AbstractCustomBatchJobHandler}
 */
public abstract class ItemCustomBatchJobHandler<T> extends AbstractCustomBatchJobHandler<T> {

    private static final Logger LOG = LoggerFactory.getLogger(ItemCustomBatchJobHandler.class);

    /** The number of failures spelled out in the message of {@link CustomBatchItemsFailedException}. */
    private static final int MESSAGE_FAILURES = 5;

    private static final int MESSAGE_LENGTH = 200;

    /**
     * Creates a handler.
     *
     * @param type the batch type, see {@link AbstractCustomBatchJobHandler#AbstractCustomBatchJobHandler}
     * @param itemType the type of one item
     */
    protected ItemCustomBatchJobHandler(String type, Type itemType) {
        super(type, itemType);
    }

    /**
     * Processes one item in a transaction of its own. An unchecked exception marks the item as failed;
     * the transaction rolls back.
     *
     * @param item the item
     * @param commandContext the context of the item's transaction
     */
    protected abstract void executeItem(T item, CommandContext commandContext);

    /**
     * Called for every item that failed, after its transaction rolled back. The default logs a warning
     * with {@link #describeItem} and the message of the failure. Override it to report the failure
     * elsewhere; throwing from it aborts the job like any other error.
     *
     * @param item the failed item
     * @param failure what {@link #executeItem} threw
     */
    protected void onItemFailure(T item, RuntimeException failure) {
        LOG.warn("Item {} of the custom batch '{}' failed: {}", describeItem(item), getType(), failure.toString());
    }

    @Override
    protected final void executeJob(
            CustomBatchConfiguration<T> job, String jobConfigurationId, CommandContext commandContext) {
        CommandExecutor isolated =
                commandContext.getProcessEngineConfiguration().getCommandExecutorTxRequiresNew();

        List<T> failedItems = new ArrayList<>();
        List<RuntimeException> failures = new ArrayList<>();
        for (T item : job.getItems()) {
            try {
                isolated.execute(context -> {
                    executeItem(item, context);
                    return null;
                });
            } catch (RuntimeException failure) {
                failedItems.add(item);
                failures.add(failure);
                onItemFailure(item, failure);
            }
        }
        if (failedItems.isEmpty()) {
            return;
        }

        byte[] remaining = writeConfiguration(job.withItems(failedItems));
        // committed on its own: the failing job rolls back, and the retry must see only the failed items
        isolated.execute(context -> {
            ByteArrayEntity stored = context.getDbEntityManager().selectById(ByteArrayEntity.class, jobConfigurationId);
            stored.setBytes(remaining);
            return null;
        });
        throw failure(job, failedItems, failures);
    }

    private CustomBatchItemsFailedException failure(
            CustomBatchConfiguration<T> job, List<T> failedItems, List<RuntimeException> failures) {
        StringBuilder message = new StringBuilder()
                .append(failedItems.size())
                .append(" of ")
                .append(job.getItems().size())
                .append(" items of the custom batch '")
                .append(getType())
                .append("' failed; the job keeps only these items. ");
        for (int i = 0; i < Math.min(MESSAGE_FAILURES, failedItems.size()); i++) {
            String reason = String.valueOf(failures.get(i).getMessage());
            message.append("Item ")
                    .append(describeItem(failedItems.get(i)))
                    .append(": ")
                    .append(
                            reason.length() <= MESSAGE_LENGTH
                                    ? reason
                                    : reason.substring(0, MESSAGE_LENGTH - 3) + "...")
                    .append(". ");
        }
        if (failedItems.size() > MESSAGE_FAILURES) {
            message.append("And ").append(failedItems.size() - MESSAGE_FAILURES).append(" more.");
        }
        CustomBatchItemsFailedException exception = new CustomBatchItemsFailedException(
                message.toString().trim(), failedItems.size(), job.getItems().size(), failures.get(0));
        failures.stream().skip(1).forEach(exception::addSuppressed);
        return exception;
    }
}
