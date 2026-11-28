/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import java.util.concurrent.atomic.LongAdder;
import org.operaton.bpm.engine.impl.metrics.Meter;

/**
 * Replaces one of the engine's database meters. The engine's meter is cleared every time the
 * metrics reporter flushes it to the database; this subclass additionally keeps a total that is
 * never cleared, which is what a Micrometer counter needs. Thread-safe.
 */
final class ObservedMeter extends Meter {

    private final LongAdder total = new LongAdder();

    ObservedMeter(Meter original) {
        super(original.getName());
        long pending = original.getAndClear();
        super.markTimes(pending);
        total.add(pending);
    }

    @Override
    public void mark() {
        super.mark();
        total.increment();
    }

    @Override
    public void markTimes(long times) {
        super.markTimes(times);
        total.add(times);
    }

    /** Occurrences since this instance was installed (monotonic). */
    double total() {
        return total.sum();
    }
}
