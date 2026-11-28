/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongPredicate;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.persistence.entity.PropertyEntity;

/**
 * Item handler whose effect is an engine property per item, written in the item's transaction: a failing
 * item must leave no property behind, the others must keep theirs, and an item must never run twice (the
 * property name is a primary key).
 */
class PropertyItemHandler extends ItemCustomBatchJobHandler<Long> {

    static final String TYPE = "property-items";
    static final String PREFIX = "batch-item-";

    final List<Long> reportedFailures = Collections.synchronizedList(new ArrayList<>());
    volatile LongPredicate failOn = item -> false;

    PropertyItemHandler() {
        super(TYPE, Long.class);
    }

    @Override
    protected void executeItem(Long item, CommandContext commandContext) {
        commandContext.getPropertyManager().insert(new PropertyEntity(PREFIX + item, "done"));
        if (failOn.test(item)) {
            throw new IllegalStateException("cannot process item " + item);
        }
    }

    @Override
    protected void onItemFailure(Long item, RuntimeException failure) {
        reportedFailures.add(item);
    }
}
