/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Converts between a Java type and the engine's {@link TypedValue}. The built-in factories of
 * {@link TypedVariables} are codecs; implement this interface to add a type of your own (for example a
 * {@code LocalDate} stored as a string) and declare it with {@link TypedVariables#variable(String, VariableCodec)}.
 *
 * <p>Implementations must be thread-safe: they are shared by every thread that uses the declaring factory.
 *
 * @param <T> the Java type
 */
public interface VariableCodec<T> {

    /**
     * Returns a human-readable name of the declared type for error messages, for example {@code Long} or
     * {@code List<Order>}.
     *
     * @return the type description
     */
    String describeType();

    /**
     * Converts a value to the typed value that is stored.
     *
     * @param variableName name of the variable, for error messages
     * @param value the value, never {@code null}
     * @return the typed value to store
     * @throws TypedVariableException if the value cannot be stored in this type
     */
    TypedValue encode(String variableName, T value);

    /**
     * Converts a stored value to the declared type.
     *
     * @param variableName name of the variable, for error messages
     * @param value the stored typed value, with a non-null {@link TypedValue#getValue() value}
     * @return the converted value, never {@code null}
     * @throws WrongVariableTypeException if the stored value does not have the declared type
     */
    T decode(String variableName, TypedValue value);
}
