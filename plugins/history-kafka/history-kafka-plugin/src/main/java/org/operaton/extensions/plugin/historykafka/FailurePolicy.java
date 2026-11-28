/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka;

/** What happens when an event cannot be handed over to Kafka after the transaction committed. */
public enum FailurePolicy {
    /**
     * Throw a {@link HistoryKafkaException} from the after-commit hook (after logging it with
     * context). The engine transaction is already committed and is <em>not</em> rolled back; the
     * exception reaches the caller of the engine command. This is the default.
     */
    FAIL,
    /** Log the failure at {@code ERROR} and count it ({@code getFailedCount()}); do not throw. */
    LOG_AND_COUNT
}
