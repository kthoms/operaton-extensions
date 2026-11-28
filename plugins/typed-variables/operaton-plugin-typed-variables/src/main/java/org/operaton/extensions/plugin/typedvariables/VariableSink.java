/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Where variables are written to. {@link VariableSinks} creates the sinks for a variable scope, a variable map and
 * for runtime and task service access; implement the interface to write somewhere else.
 */
public interface VariableSink {

    /**
     * Sets a variable in the scope this sink writes to (the engine propagates it as for {@code setVariable}).
     *
     * @param name the variable name
     * @param value the typed value
     */
    void set(String name, TypedValue value);

    /**
     * Sets a variable in the local scope.
     *
     * @param name the variable name
     * @param value the typed value
     * @throws UnsupportedOperationException if this sink has no notion of a local scope
     */
    void setLocal(String name, TypedValue value);

    /**
     * Removes a variable.
     *
     * @param name the variable name
     */
    void remove(String name);

    /**
     * Removes a variable of the local scope.
     *
     * @param name the variable name
     * @throws UnsupportedOperationException if this sink has no notion of a local scope
     */
    void removeLocal(String name);

    /**
     * Describes the sink for error messages.
     *
     * @return the description
     */
    String describe();
}
