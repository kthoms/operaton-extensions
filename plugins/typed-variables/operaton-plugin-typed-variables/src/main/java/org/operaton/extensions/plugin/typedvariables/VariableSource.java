/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Where variables are read from. {@link VariableSources} creates the sources for a variable scope, a variable map,
 * an external task and for runtime and task service lookups; implement the interface to read from somewhere else
 * (the test module reads historic variables this way).
 */
public interface VariableSource {

    /**
     * Reads a variable from this source, including the scopes above it.
     *
     * @param name the variable name
     * @return the typed value, or {@code null} if the variable does not exist
     */
    TypedValue getTyped(String name);

    /**
     * Reads a variable of the local scope only.
     *
     * @param name the variable name
     * @return the typed value, or {@code null} if the local scope has no such variable
     * @throws UnsupportedOperationException if this source has no notion of a local scope
     */
    TypedValue getLocalTyped(String name);

    /**
     * Describes the source for error messages, for example {@code execution 4711 (activity validate)}.
     *
     * @return the description
     */
    String describe();
}
