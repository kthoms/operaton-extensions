/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

/**
 * Thrown when an LLM call cannot be made or its result cannot be used: invalid configuration or
 * input, a failed provider call, output that does not validate, or an exhausted agent loop.
 * Inside a service task it reaches the engine, so the job fails, is retried and finally creates an
 * incident.
 */
public class AiLlmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiLlmException(String message) {
        super(message);
    }

    public AiLlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
