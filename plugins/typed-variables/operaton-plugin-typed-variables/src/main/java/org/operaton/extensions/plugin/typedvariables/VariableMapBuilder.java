/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import org.operaton.bpm.engine.variable.VariableMap;
import org.operaton.bpm.engine.variable.Variables;

/**
 * Builds a {@link VariableMap} from declared variables, for starting process instances, completing tasks, completing
 * external tasks and for tests.
 *
 * <pre>{@code
 * VariableMap variables = TypedVariables.builder()
 *         .set(ORDER_ID, "A-1")
 *         .set(TOTAL_CENTS, 1999L)
 *         .build();
 * }</pre>
 *
 * <p>A builder is not thread-safe; use one per thread.
 */
public final class VariableMapBuilder {

    private final VariableMap variables = Variables.createVariables();

    VariableMapBuilder() {}

    /**
     * Sets a variable.
     *
     * @param variable the declaration
     * @param value the value, not {@code null}
     * @param <T> the Java type
     * @return this builder
     */
    public <T> VariableMapBuilder set(VariableFactory<T> variable, T value) {
        Objects.requireNonNull(variable, "variable");
        variable.on(variables).set(value);
        return this;
    }

    /**
     * Sets a variable when the value is not {@code null}, and does nothing otherwise. For values that are
     * optional by design.
     *
     * @param variable the declaration
     * @param value the value, or {@code null} to leave the variable out
     * @param <T> the Java type
     * @return this builder
     */
    public <T> VariableMapBuilder setIfNotNull(VariableFactory<T> variable, T value) {
        if (value != null) {
            set(variable, value);
        }
        return this;
    }

    /**
     * Returns the map built so far. The builder can be used on; the returned map is a copy.
     *
     * @return a new map with the typed values
     */
    public VariableMap build() {
        VariableMap copy = Variables.createVariables();
        for (String name : variables.keySet()) {
            copy.putValueTyped(name, variables.getValueTyped(name));
        }
        return copy;
    }
}
