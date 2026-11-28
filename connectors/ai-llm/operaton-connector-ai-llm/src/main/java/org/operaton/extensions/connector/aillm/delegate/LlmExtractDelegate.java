/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.delegate;

import java.util.Map;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.extensions.connector.aillm.LlmOperation;

/**
 * JavaDelegate alternative to the {@code llm-extract} connector: writes the validated JSON document
 * to {@code resultVariable}, as a JSON object value ({@code resultFormat} {@code json}, the default)
 * or as JSON text ({@code string}).
 */
public class LlmExtractDelegate extends AbstractLlmDelegate {

    protected Expression jsonSchema;
    protected Expression retries;
    protected Expression resultFormat;

    @Override
    protected LlmOperation operation() {
        return LlmOperation.EXTRACT;
    }

    @Override
    protected void addOperationInputs(Map<String, Expression> fields) {
        fields.put(LlmOperation.JSON_SCHEMA, jsonSchema);
        fields.put(LlmOperation.RETRIES, retries);
        fields.put(LlmOperation.RESULT_FORMAT, resultFormat);
    }
}
