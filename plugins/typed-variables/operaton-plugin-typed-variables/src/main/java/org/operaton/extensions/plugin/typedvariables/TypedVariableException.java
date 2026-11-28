/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

/**
 * Base class of the failures raised by this extension. It is a {@link RuntimeException}: thrown from a delegate or
 * listener it fails the job or command, so the engine retries and creates an incident.
 */
public class TypedVariableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String variableName;

    /**
     * Creates the exception.
     *
     * @param variableName name of the variable concerned
     * @param message what went wrong, including where the variable was looked up
     */
    public TypedVariableException(String variableName, String message) {
        super(message);
        this.variableName = variableName;
    }

    /**
     * Creates the exception with a cause.
     *
     * @param variableName name of the variable concerned
     * @param message what went wrong, including where the variable was looked up
     * @param cause the underlying failure
     */
    public TypedVariableException(String variableName, String message, Throwable cause) {
        super(message, cause);
        this.variableName = variableName;
    }

    /**
     * Returns the name of the variable concerned.
     *
     * @return the variable name
     */
    public String getVariableName() {
        return variableName;
    }
}
