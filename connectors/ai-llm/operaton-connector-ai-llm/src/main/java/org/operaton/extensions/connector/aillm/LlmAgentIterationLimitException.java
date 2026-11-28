/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/** The agent still requested tools when its maximum number of model calls was reached. */
public class LlmAgentIterationLimitException extends AiLlmException {

    private static final long serialVersionUID = 1L;

    private final int maxIterations;

    public LlmAgentIterationLimitException(int maxIterations, int toolCalls) {
        super("The agent did not produce a final answer within maxIterations=" + maxIterations + " model calls ("
                + toolCalls + " tool call(s) made); raise maxIterations or narrow the task");
        this.maxIterations = maxIterations;
    }

    public int getMaxIterations() {
        return maxIterations;
    }
}
