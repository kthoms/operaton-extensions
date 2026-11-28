/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * class org.camunda.community.batch.core.CustomBatchConfiguration; extended with the batch id and
 * a batch id.
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The persisted configuration of a custom batch, and of each of its batch execution jobs.
 *
 * <p>The batch itself stores the items that still have to be turned into jobs (the seed job removes
 * them as it creates jobs); every batch execution job stores its own slice of the items. Both are
 * the same JSON document, written by {@link AbstractCustomBatchJobHandler}.
 *
 * <p>Instances are not thread-safe; the engine only touches them inside one command.
 *
 * @param <T> the item type
 */
public final class CustomBatchConfiguration<T> {

    private final List<T> items;
    private final boolean exclusive;
    private final String batchId;

    /**
     * Creates a configuration.
     *
     * @param items the items; copied, must not contain {@code null}
     * @param exclusive whether the batch execution jobs are exclusive (one at a time per batch)
     * @param batchId the id of the batch the jobs belong to; {@code null} until the batch is persisted
     */
    public CustomBatchConfiguration(List<? extends T> items, boolean exclusive, String batchId) {
        Objects.requireNonNull(items, "items");
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == null) {
                throw new IllegalArgumentException("Custom batch items must not be null, but item " + i + " is");
            }
        }
        this.items = new ArrayList<>(items);
        this.exclusive = exclusive;
        this.batchId = batchId;
    }

    /** The items of this batch (or job); an unmodifiable view. */
    public List<T> getItems() {
        return Collections.unmodifiableList(items);
    }

    /** Whether the batch execution jobs are exclusive. */
    public boolean isExclusive() {
        return exclusive;
    }

    /** The id of the batch the jobs belong to; {@code null} in the configuration of a batch not yet persisted. */
    public String getBatchId() {
        return batchId;
    }

    /** The live item list; only the seed job removes processed items from it. */
    List<T> mutableItems() {
        return items;
    }

    /** A configuration for one batch execution job: the given slice of items, the same settings. */
    CustomBatchConfiguration<T> forJob(List<T> jobItems, String ownerBatchId) {
        return new CustomBatchConfiguration<>(jobItems, exclusive, ownerBatchId);
    }

    /** The same settings with other items. */
    CustomBatchConfiguration<T> withItems(List<T> otherItems) {
        return new CustomBatchConfiguration<>(otherItems, exclusive, batchId);
    }
}
