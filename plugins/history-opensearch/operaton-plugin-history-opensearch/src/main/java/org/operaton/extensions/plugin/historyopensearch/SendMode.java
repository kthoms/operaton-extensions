/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

/** How the documents of a committed transaction reach OpenSearch. */
public enum SendMode {
    /**
     * Queue the documents; a background thread writes them in bulk requests (by batch size or flush
     * interval). Cheapest for the engine, but documents of a crashed JVM that are still queued are lost
     * and write errors cannot reach the caller. This is the default.
     */
    ASYNC,
    /**
     * Write the documents of the transaction, with retries, before the engine command returns. Blocks
     * the engine thread; write errors follow the {@link FailurePolicy}.
     */
    SYNC
}
