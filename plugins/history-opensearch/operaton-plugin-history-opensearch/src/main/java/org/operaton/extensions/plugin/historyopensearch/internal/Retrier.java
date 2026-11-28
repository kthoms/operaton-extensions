/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries calls that fail with a retryable {@link RestException}, with exponential back-off. Thread-safe. */
final class Retrier {

    private static final Logger LOG = LoggerFactory.getLogger(Retrier.class);

    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final LongAdder retries = new LongAdder();

    Retrier(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
    }

    int maxRetries() {
        return maxRetries;
    }

    /** How many retries (waits followed by another attempt) happened so far. */
    long retries() {
        return retries.sum();
    }

    /** Runs the call; a retryable failure is retried up to {@code maxRetries} times, the last failure is thrown. */
    <T> T call(String what, Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try {
                return action.get();
            } catch (RestException e) {
                if (!e.isRetryable() || attempt >= maxRetries) {
                    throw e;
                }
                LOG.warn(
                        "history-opensearch: {} failed ({}); retry {} of {}",
                        what,
                        e.getMessage(),
                        attempt + 1,
                        maxRetries);
                backoff(attempt, e);
            }
        }
    }

    /** Waits the back-off for the given retry (0-based), or throws when the thread is interrupted. */
    void backoff(int attempt, RestException cause) {
        retries.increment();
        long millis = Math.min(maxBackoff.toMillis(), initialBackoff.toMillis() << Math.min(attempt, 20));
        try {
            Thread.sleep(Math.max(1, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            RestException interrupted =
                    new RestException("interrupted while waiting to retry: " + cause.getMessage(), -1, false, e);
            interrupted.addSuppressed(cause);
            throw interrupted;
        }
    }
}
