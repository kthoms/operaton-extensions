/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

/** What happens when history documents cannot be written to OpenSearch after the transaction committed. */
public enum FailurePolicy {
    /**
     * Throw a {@link HistoryOpenSearchException} from the after-commit hook (after logging it with
     * context). The engine transaction is already committed and is <em>not</em> rolled back; the
     * exception reaches the caller of the engine command. This is the default. In {@link
     * SendMode#ASYNC} only the hand-over to the background writer can fail this way (queue full,
     * plugin closed); write errors happen later and are logged and counted.
     */
    FAIL,
    /** Log the failure at {@code ERROR} and count it ({@code getFailedCount()}); do not throw. */
    LOG_AND_COUNT
}
