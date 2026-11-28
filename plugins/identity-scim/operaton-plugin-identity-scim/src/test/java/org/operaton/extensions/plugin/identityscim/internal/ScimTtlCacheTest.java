/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ScimTtlCacheTest {

    private final AtomicLong clock = new AtomicLong();

    @Test
    void returnsValuesUntilTheTtlElapsed() {
        ScimTtlCache<String> cache = new ScimTtlCache<>(Duration.ofSeconds(10), 10, clock::get);
        cache.put("k", "v");
        clock.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(cache.get("k")).isEqualTo("v");
        clock.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(cache.get("k")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictsTheOldestEntryAtCapacity() {
        ScimTtlCache<String> cache = new ScimTtlCache<>(Duration.ofSeconds(10), 2, clock::get);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isEqualTo("2");
        assertThat(cache.get("c")).isEqualTo("3");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void dropsExpiredEntriesBeforeEvictingLiveOnes() {
        ScimTtlCache<String> cache = new ScimTtlCache<>(Duration.ofSeconds(10), 2, clock::get);
        cache.put("old", "1");
        clock.addAndGet(Duration.ofSeconds(6).toNanos());
        cache.put("young", "2");
        clock.addAndGet(Duration.ofSeconds(5).toNanos());
        cache.put("new", "3");
        assertThat(cache.get("young")).isEqualTo("2");
        assertThat(cache.get("new")).isEqualTo("3");
        assertThat(cache.get("old")).isNull();
    }

    @Test
    void aZeroTtlDisablesTheCache() {
        ScimTtlCache<String> cache = new ScimTtlCache<>(Duration.ZERO, 10, clock::get);
        cache.put("k", "v");
        assertThat(cache.get("k")).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void clearRemovesEverything() {
        ScimTtlCache<String> cache = new ScimTtlCache<>(Duration.ofSeconds(10), 10, clock::get);
        cache.put("k", "v");
        cache.clear();
        assertThat(cache.get("k")).isNull();
    }
}
