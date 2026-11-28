/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

/** How the after-commit hook hands events over to the Kafka producer. */
public enum SendMode {
    /**
     * Hand the records to the producer's buffer and return. Delivery failures are reported on the
     * producer thread: they are logged and counted, but cannot reach the caller (at-most-once from
     * the engine's point of view; the producer's own retries apply).
     */
    ASYNC,
    /**
     * Wait, up to the configured timeout, until the broker acknowledged every record of the
     * transaction. Failures and timeouts follow the {@link FailurePolicy} (at-least-once
     * semantics for the caller, who can retry). Blocks the engine thread.
     */
    SYNC
}
