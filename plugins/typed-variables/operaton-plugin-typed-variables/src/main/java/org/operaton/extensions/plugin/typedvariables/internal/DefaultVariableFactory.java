/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.internal;

import java.util.Objects;
import org.operaton.extensions.plugin.typedvariables.VariableCodec;
import org.operaton.extensions.plugin.typedvariables.VariableFactory;

/**
 * The only implementation of {@link VariableFactory}: a name and a codec. Immutable, hence thread-safe.
 *
 * @param <T> the Java type of the variable
 */
public final class DefaultVariableFactory<T> implements VariableFactory<T> {

    private final String name;
    private final VariableCodec<T> codec;

    /**
     * Creates the factory.
     *
     * @param name the variable name, not blank
     * @param codec the codec of the type
     */
    public DefaultVariableFactory(String name, VariableCodec<T> codec) {
        Objects.requireNonNull(name, "variable name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A variable name must not be blank");
        }
        this.name = name;
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public VariableCodec<T> getCodec() {
        return codec;
    }

    @Override
    public String toString() {
        return "VariableFactory[" + name + ": " + codec.describeType() + "]";
    }
}
