/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.tool;

/**
 * A tool call the model made cannot be served as requested (invalid or missing arguments, a value
 * that is not allowed). The message is sent back to the model as the tool result, so it can correct
 * its request; it does not fail the task. Every other exception from a tool does.
 */
public class ToolRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolRefusedException(String message) {
        super(message);
    }
}
