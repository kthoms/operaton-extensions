/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.Map;
import java.util.Objects;
import org.operaton.bpm.engine.delegate.VariableScope;

/** Read access to named values: the variables prompt templates and the variable tool see. */
public interface VariableSource {

    /** Whether a value with this name exists (it may be {@code null}). */
    boolean has(String name);

    /** The value, or {@code null} if it is {@code null}; call {@link #has(String)} first. */
    Object get(String name);

    /** The variables visible from a variable scope (an execution, including its parents). */
    static VariableSource of(VariableScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new VariableSource() {
            @Override
            public boolean has(String name) {
                return scope.hasVariable(name);
            }

            @Override
            public Object get(String name) {
                return scope.getVariable(name);
            }
        };
    }

    /** The entries of a map. */
    static VariableSource of(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        return new VariableSource() {
            @Override
            public boolean has(String name) {
                return values.containsKey(name);
            }

            @Override
            public Object get(String name) {
                return values.get(name);
            }
        };
    }

    /** A source without values: every template placeholder fails. */
    static VariableSource none() {
        return of(Map.of());
    }
}
