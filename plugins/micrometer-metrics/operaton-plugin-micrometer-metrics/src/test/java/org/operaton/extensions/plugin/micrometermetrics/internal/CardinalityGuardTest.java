/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CardinalityGuardTest {

    @Test
    void admitsUpToTheLimitThenReportsOverflow() {
        CardinalityGuard guard = new CardinalityGuard("key", 2, "other");

        assertThat(guard.admit("a")).isEqualTo("a");
        assertThat(guard.admit("b")).isEqualTo("b");
        assertThat(guard.admit("c")).isEqualTo("other");
        assertThat(guard.admit("a")).isEqualTo("a");
    }

    @Test
    void nullAndEmptyBecomeUnknown() {
        CardinalityGuard guard = new CardinalityGuard("key", 5, "other");

        assertThat(guard.admit(null)).isEqualTo("unknown");
        assertThat(guard.admit("")).isEqualTo("unknown");
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThatThrownBy(() -> new CardinalityGuard("key", 0, "other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key");
    }

    @Test
    void neverAdmitsMoreThanTheLimitUnderContention() throws InterruptedException {
        CardinalityGuard guard = new CardinalityGuard("key", 10, "other");
        Set<String> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        IntStream.range(0, 1000).forEach(i -> pool.execute(() -> seen.add(guard.admit("v" + i))));
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(seen).hasSize(11).contains("other");
    }
}
