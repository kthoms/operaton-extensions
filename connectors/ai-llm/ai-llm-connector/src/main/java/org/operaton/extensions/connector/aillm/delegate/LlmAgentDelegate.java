/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.delegate;

import java.util.Map;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.extensions.connector.aillm.LlmOperation;

/** JavaDelegate alternative to the {@code llm-agent} connector: writes the final answer to {@code resultVariable}. */
public class LlmAgentDelegate extends AbstractLlmDelegate {

    protected Expression tools;
    protected Expression readableVariables;
    protected Expression maxIterations;

    @Override
    protected LlmOperation operation() {
        return LlmOperation.AGENT;
    }

    @Override
    protected void addOperationInputs(Map<String, Expression> fields) {
        fields.put(LlmOperation.TOOLS, tools);
        fields.put(LlmOperation.READABLE_VARIABLES, readableVariables);
        fields.put(LlmOperation.MAX_ITERATIONS, maxIterations);
    }
}
