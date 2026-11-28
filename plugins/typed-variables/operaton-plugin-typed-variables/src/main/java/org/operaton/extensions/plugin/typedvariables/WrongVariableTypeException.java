/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

/** Raised when a variable exists but its value does not have the declared type. */
public class WrongVariableTypeException extends TypedVariableException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param variableName name of the variable
     * @param expected the declared type, for example {@code Long} or {@code List<Order>}
     * @param actual description of what is stored, for example the Java class of the value
     * @param hint how to resolve it, may be empty
     */
    public WrongVariableTypeException(String variableName, String expected, String actual, String hint) {
        super(
                variableName,
                "Variable '" + variableName + "' is declared as " + expected + " but holds " + actual
                        + (hint.isEmpty() ? "" : ": " + hint));
    }

    /**
     * Creates the exception with a cause.
     *
     * @param variableName name of the variable
     * @param expected the declared type
     * @param actual description of what is stored
     * @param hint how to resolve it, may be empty
     * @param cause the underlying failure
     */
    public WrongVariableTypeException(
            String variableName, String expected, String actual, String hint, Throwable cause) {
        super(
                variableName,
                "Variable '" + variableName + "' is declared as " + expected + " but holds " + actual
                        + (hint.isEmpty() ? "" : ": " + hint),
                cause);
    }
}
