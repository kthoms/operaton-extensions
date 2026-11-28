/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;
import java.util.Optional;
import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Reads one declared variable from one {@link VariableSource}. Created by the {@code from(...)} methods of
 * {@link VariableFactory}.
 *
 * <p>A variable counts as absent when it does not exist or exists with a {@code null} value. A variable that exists
 * with a value of the wrong type is never absent: reading it throws {@link WrongVariableTypeException}, also in the
 * {@code Optional} and default-value variants.
 *
 * <p>Instances are immutable; thread-safety is that of the underlying source.
 *
 * @param <T> the declared Java type
 */
public final class ReadAdapter<T> {

    private final VariableFactory<T> factory;
    private final VariableSource source;

    /**
     * Creates the adapter.
     *
     * @param factory the variable declaration
     * @param source where to read from
     */
    public ReadAdapter(VariableFactory<T> factory, VariableSource source) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Reads the variable; it must exist.
     *
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the variable is missing or {@code null}
     * @throws WrongVariableTypeException if the value has another type
     */
    public T get() {
        return getOptional()
                .orElseThrow(() -> new VariableNotFoundException(factory.getName(), source.describe(), false));
    }

    /**
     * Reads the variable.
     *
     * @return the value, or empty if the variable is missing or {@code null}
     * @throws WrongVariableTypeException if the value has another type
     */
    public Optional<T> getOptional() {
        return decode(source.getTyped(factory.getName()));
    }

    /**
     * Reads the variable, falling back to a default when it is absent.
     *
     * @param defaultValue the value to return when the variable is missing or {@code null}
     * @return the value or the default
     * @throws WrongVariableTypeException if the value has another type
     */
    public T getOrDefault(T defaultValue) {
        return getOptional().orElse(defaultValue);
    }

    /**
     * Tells whether the variable exists with a value.
     *
     * @return {@code true} if present and not {@code null}
     */
    public boolean isPresent() {
        TypedValue typed = source.getTyped(factory.getName());
        return typed != null && typed.getValue() != null;
    }

    /**
     * Reads the variable from the local scope only; it must exist there.
     *
     * @return the value, never {@code null}
     * @throws VariableNotFoundException if the local scope has no such variable or it is {@code null}
     * @throws WrongVariableTypeException if the value has another type
     * @throws UnsupportedOperationException if the source has no local scope
     */
    public T getLocal() {
        return getLocalOptional()
                .orElseThrow(() -> new VariableNotFoundException(factory.getName(), source.describe(), true));
    }

    /**
     * Reads the variable from the local scope only.
     *
     * @return the value, or empty if absent in the local scope
     * @throws WrongVariableTypeException if the value has another type
     * @throws UnsupportedOperationException if the source has no local scope
     */
    public Optional<T> getLocalOptional() {
        return decode(source.getLocalTyped(factory.getName()));
    }

    /**
     * Reads the variable from the local scope only, falling back to a default.
     *
     * @param defaultValue the value to return when the local scope has no such variable
     * @return the value or the default
     */
    public T getLocalOrDefault(T defaultValue) {
        return getLocalOptional().orElse(defaultValue);
    }

    /**
     * Tells whether the local scope has the variable with a value.
     *
     * @return {@code true} if present locally and not {@code null}
     * @throws UnsupportedOperationException if the source has no local scope
     */
    public boolean isLocalPresent() {
        TypedValue typed = source.getLocalTyped(factory.getName());
        return typed != null && typed.getValue() != null;
    }

    private Optional<T> decode(TypedValue typed) {
        if (typed == null || typed.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(factory.getCodec().decode(factory.getName(), typed));
    }
}
