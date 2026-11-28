/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import org.operaton.bpm.engine.ProcessEngineException;

/** A history event could not be converted, or its document could not be written to OpenSearch. */
public class HistoryOpenSearchException extends ProcessEngineException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message. */
    public HistoryOpenSearchException(String message) {
        super(message);
    }

    /** Creates the exception with a message and the underlying cause. */
    public HistoryOpenSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
