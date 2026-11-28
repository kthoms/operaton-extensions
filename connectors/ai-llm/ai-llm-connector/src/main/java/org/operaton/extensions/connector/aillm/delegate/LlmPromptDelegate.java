/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.delegate;

import java.util.Map;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.extensions.connector.aillm.LlmOperation;

/** JavaDelegate alternative to the {@code llm-prompt} connector: writes the text answer to {@code resultVariable}. */
public class LlmPromptDelegate extends AbstractLlmDelegate {

    @Override
    protected LlmOperation operation() {
        return LlmOperation.PROMPT;
    }

    @Override
    protected void addOperationInputs(Map<String, Expression> fields) {
        // only the common fields
    }
}
