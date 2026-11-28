/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Objects;

/**
 * Writes one declared variable to one {@link VariableSink}. Created by the {@code on(...)} methods of
 * {@link VariableFactory}.
 *
 * <p>{@code null} is never written: a variable that exists with a {@code null} value is indistinguishable from a
 * missing one for this extension, so {@code set(null)} fails and {@link #remove()} is the way to clear a variable.
 *
 * <p>Instances are immutable; thread-safety is that of the underlying sink.
 *
 * @param <T> the declared Java type
 */
public final class WriteAdapter<T> {

    private final VariableFactory<T> factory;
    private final VariableSink sink;

    /**
     * Creates the adapter.
     *
     * @param factory the variable declaration
     * @param sink where to write to
     */
    public WriteAdapter(VariableFactory<T> factory, VariableSink sink) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Sets the variable.
     *
     * @param value the value, not {@code null}
     * @throws IllegalArgumentException if the value is {@code null}
     * @throws TypedVariableException if the value cannot be stored in the declared type
     */
    public void set(T value) {
        sink.set(factory.getName(), factory.toTypedValue(value));
    }

    /**
     * Sets the variable in the local scope.
     *
     * @param value the value, not {@code null}
     * @throws IllegalArgumentException if the value is {@code null}
     * @throws UnsupportedOperationException if the sink has no local scope
     */
    public void setLocal(T value) {
        sink.setLocal(factory.getName(), factory.toTypedValue(value));
    }

    /** Removes the variable. */
    public void remove() {
        sink.remove(factory.getName());
    }

    /**
     * Removes the variable from the local scope.
     *
     * @throws UnsupportedOperationException if the sink has no local scope
     */
    public void removeLocal() {
        sink.removeLocal(factory.getName());
    }
}
