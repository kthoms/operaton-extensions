/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.operaton.extensions.plugin.typedvariables.internal.DefaultVariableFactory;
import org.operaton.extensions.plugin.typedvariables.internal.ObjectCodecs;
import org.operaton.extensions.plugin.typedvariables.internal.ValueCodecs;

/**
 * Entry point: declares variables. Static-import the methods you need:
 *
 * <pre>{@code
 * public final class OrderVariables {
 *     public static final VariableFactory<String> ORDER_ID = stringVariable("orderId");
 *     public static final VariableFactory<Long> TOTAL_CENTS = longVariable("totalCents");
 *     public static final VariableFactory<Order> ORDER = customVariable("order", Order.class);
 *     public static final VariableFactory<List<String>> ERRORS = listVariable("errors", String.class);
 * }
 * }</pre>
 *
 * <p>Declared variables are immutable and thread-safe, so they are safe as {@code static final} constants.
 */
public final class TypedVariables {

    private TypedVariables() {}

    /**
     * Declares a {@code String} variable.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<String> stringVariable(String name) {
        return variable(name, ValueCodecs.STRING);
    }

    /**
     * Declares a {@code Long} variable. Values stored as {@code Integer}, {@code Short} or {@code Byte} by other
     * code are read as {@code Long}.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<Long> longVariable(String name) {
        return variable(name, ValueCodecs.LONG);
    }

    /**
     * Declares an {@code Integer} variable. A {@code Long} value within the int range is read as {@code Integer}.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<Integer> intVariable(String name) {
        return variable(name, ValueCodecs.INTEGER);
    }

    /**
     * Declares a {@code Double} variable.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<Double> doubleVariable(String name) {
        return variable(name, ValueCodecs.DOUBLE);
    }

    /**
     * Declares a {@code Boolean} variable.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<Boolean> booleanVariable(String name) {
        return variable(name, ValueCodecs.BOOLEAN);
    }

    /**
     * Declares a {@link Date} variable.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<Date> dateVariable(String name) {
        return variable(name, ValueCodecs.DATE);
    }

    /**
     * Declares a {@link UUID} variable, stored as a string.
     *
     * @param name the variable name
     * @return the factory
     */
    public static VariableFactory<UUID> uuidVariable(String name) {
        return variable(name, ValueCodecs.UUID_CODEC);
    }

    /**
     * Declares an enum variable, stored as the name of the constant.
     *
     * @param name the variable name
     * @param type the enum class
     * @param <E> the enum type
     * @return the factory
     */
    public static <E extends Enum<E>> VariableFactory<E> enumVariable(String name, Class<E> type) {
        return variable(name, ValueCodecs.enumCodec(type));
    }

    /**
     * Declares a variable holding an object of your own type, stored as JSON when Spin is available and by Java
     * serialization otherwise ({@link SerializationFormat#AUTO}).
     *
     * @param name the variable name
     * @param type the class of the object
     * @param <T> the object type
     * @return the factory
     * @throws IllegalArgumentException if Java serialization would be used and the class is not {@code Serializable}
     */
    public static <T> VariableFactory<T> customVariable(String name, Class<T> type) {
        return customVariable(name, type, SerializationFormat.AUTO);
    }

    /**
     * Declares a variable holding an object of your own type with an explicit storage format.
     *
     * @param name the variable name
     * @param type the class of the object
     * @param format how to store it
     * @param <T> the object type
     * @return the factory
     * @throws IllegalStateException if {@link SerializationFormat#JSON} is requested and Spin is missing
     * @throws IllegalArgumentException if Java serialization is used and the class is not {@code Serializable}
     */
    public static <T> VariableFactory<T> customVariable(String name, Class<T> type, SerializationFormat format) {
        return variable(name, ObjectCodecs.object(type, format));
    }

    /**
     * Declares a list variable ({@link SerializationFormat#AUTO}). Elements must not be {@code null}.
     *
     * @param name the variable name
     * @param elementType the class of the elements
     * @param <E> the element type
     * @return the factory
     */
    public static <E> VariableFactory<List<E>> listVariable(String name, Class<E> elementType) {
        return listVariable(name, elementType, SerializationFormat.AUTO);
    }

    /**
     * Declares a list variable with an explicit storage format.
     *
     * @param name the variable name
     * @param elementType the class of the elements
     * @param format how to store it
     * @param <E> the element type
     * @return the factory
     */
    public static <E> VariableFactory<List<E>> listVariable(
            String name, Class<E> elementType, SerializationFormat format) {
        return variable(name, ObjectCodecs.list(elementType, format));
    }

    /**
     * Declares a set variable ({@link SerializationFormat#AUTO}); the set keeps its insertion order. Elements must not
     * be {@code null}.
     *
     * @param name the variable name
     * @param elementType the class of the elements
     * @param <E> the element type
     * @return the factory
     */
    public static <E> VariableFactory<Set<E>> setVariable(String name, Class<E> elementType) {
        return setVariable(name, elementType, SerializationFormat.AUTO);
    }

    /**
     * Declares a set variable with an explicit storage format.
     *
     * @param name the variable name
     * @param elementType the class of the elements
     * @param format how to store it
     * @param <E> the element type
     * @return the factory
     */
    public static <E> VariableFactory<Set<E>> setVariable(
            String name, Class<E> elementType, SerializationFormat format) {
        return variable(name, ObjectCodecs.set(elementType, format));
    }

    /**
     * Declares a variable of a type you convert yourself.
     *
     * @param name the variable name
     * @param codec the codec of the type
     * @param <T> the Java type
     * @return the factory
     */
    public static <T> VariableFactory<T> variable(String name, VariableCodec<T> codec) {
        return new DefaultVariableFactory<>(name, codec);
    }

    /**
     * Starts building a {@link org.operaton.bpm.engine.variable.VariableMap} from typed variables.
     *
     * @return a new builder
     */
    public static VariableMapBuilder builder() {
        return new VariableMapBuilder();
    }
}
