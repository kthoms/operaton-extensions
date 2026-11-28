/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException;

/** A request to OpenSearch failed; {@link #isRetryable()} tells whether trying again can help. */
public final class RestException extends HistoryOpenSearchException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final boolean retryable;

    /**
     * Creates the exception.
     *
     * @param status the HTTP status, or {@code -1} when there was no response (I/O failure)
     * @param retryable whether a later attempt may succeed (I/O failure, overload, gateway errors)
     */
    public RestException(String message, int status, boolean retryable, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
    }

    /** The HTTP status, or {@code -1} when there was no response. */
    public int getStatus() {
        return status;
    }

    /** Whether a later attempt may succeed. */
    public boolean isRetryable() {
        return retryable;
    }
}
