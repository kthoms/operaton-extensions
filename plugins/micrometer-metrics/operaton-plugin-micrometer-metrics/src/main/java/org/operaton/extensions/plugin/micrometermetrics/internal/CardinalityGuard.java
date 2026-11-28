/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounds the number of distinct values of one tag. The first {@code limit} distinct values are
 * admitted; every later new value is reported as the overflow value. Thread-safe.
 */
public final class CardinalityGuard {

    private static final Logger LOG = LoggerFactory.getLogger(CardinalityGuard.class);
    private static final String UNKNOWN = "unknown";

    private final String tagKey;
    private final int limit;
    private final String overflowValue;
    private final Set<String> admitted = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean overflowLogged = new AtomicBoolean();

    public CardinalityGuard(String tagKey, int limit, String overflowValue) {
        if (limit < 1) {
            throw new IllegalArgumentException("Cardinality limit of tag '" + tagKey + "' must be >= 1, was " + limit);
        }
        this.tagKey = tagKey;
        this.limit = limit;
        this.overflowValue = overflowValue;
    }

    /** Returns the value to use as tag value: the value itself while within the limit, else the overflow value. */
    public String admit(String value) {
        String candidate = value == null || value.isEmpty() ? UNKNOWN : value;
        if (admitted.contains(candidate)) {
            return candidate;
        }
        synchronized (admitted) {
            if (admitted.contains(candidate)) {
                return candidate;
            }
            if (admitted.size() < limit) {
                admitted.add(candidate);
                return candidate;
            }
        }
        if (overflowLogged.compareAndSet(false, true)) {
            LOG.warn(
                    "Tag '{}' reached its limit of {} distinct values; further values are reported as '{}'. "
                            + "Raise the limit if this is expected.",
                    tagKey,
                    limit,
                    overflowValue);
        }
        return overflowValue;
    }

    public String overflowValue() {
        return overflowValue;
    }
}
