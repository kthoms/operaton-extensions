/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Collection;

/** Raised when a required variable does not exist in the scope it is read from, or exists with a {@code null} value. */
public class VariableNotFoundException extends TypedVariableException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one variable.
     *
     * @param variableName name of the missing variable
     * @param source description of where it was looked up, for example {@code execution 4711 (activity validate)}
     * @param local whether only the local scope was searched
     */
    public VariableNotFoundException(String variableName, String source, boolean local) {
        super(
                variableName,
                "Required " + (local ? "local " : "") + "variable '" + variableName + "' is missing or null in "
                        + source);
    }

    /**
     * Creates the exception for several variables checked together.
     *
     * @param variableNames names of all missing variables
     * @param source description of where they were looked up
     */
    public VariableNotFoundException(Collection<String> variableNames, String source) {
        super(
                String.join(", ", variableNames),
                "Required variables are missing or null in " + source + ": " + String.join(", ", variableNames));
    }
}
