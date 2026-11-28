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

/** Chunk handler that records the chunks it processed, and can be told to fail on an item. */
class RecordingHandler extends CustomBatchJobHandler<Long> {

    static final String TYPE = "recording-chunks";

    final List<List<Long>> chunks = Collections.synchronizedList(new ArrayList<>());
    volatile LongPredicate failOn = item -> false;

    RecordingHandler() {
        super(TYPE, Long.class);
    }

    @Override
    protected void executeItems(List<Long> items, CommandContext commandContext) {
        for (Long item : items) {
            if (failOn.test(item)) {
                throw new IllegalStateException("cannot process item " + item);
            }
        }
        // recorded after the whole chunk worked, like a transaction that commits as a whole
        chunks.add(List.copyOf(items));
    }

    List<Long> processed() {
        synchronized (chunks) {
            return chunks.stream().flatMap(List::stream).toList();
        }
    }

    void reset() {
        chunks.clear();
        failOn = item -> false;
    }
}
