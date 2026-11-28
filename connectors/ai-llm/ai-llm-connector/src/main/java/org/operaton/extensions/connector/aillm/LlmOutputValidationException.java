/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.List;

/**
 * The model's answer was not a JSON document valid against the requested JSON Schema, also after
 * the configured number of retries.
 */
public class LlmOutputValidationException extends AiLlmException {

    private static final long serialVersionUID = 1L;

    private final int attempts;
    private final List<String> problems;

    public LlmOutputValidationException(int attempts, List<String> problems) {
        super("The model output did not validate against the JSON schema after " + attempts + " attempt(s): "
                + String.join("; ", problems));
        this.attempts = attempts;
        this.problems = List.copyOf(problems);
    }

    /** Number of model calls made, the first one included. */
    public int getAttempts() {
        return attempts;
    }

    /** What was wrong with the last answer. */
    public List<String> getProblems() {
        return problems;
    }
}
