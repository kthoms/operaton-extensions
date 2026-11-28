/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.internal;

import java.util.Date;
import java.util.UUID;
import java.util.function.Function;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.typedvariables.VariableCodec;
import org.operaton.extensions.plugin.typedvariables.WrongVariableTypeException;

/** The codecs of the primitive-like types: string, numbers, boolean, date, UUID and enums. */
public final class ValueCodecs {

    private ValueCodecs() {}

    /** Codec of {@link String}. */
    public static final VariableCodec<String> STRING =
            new ConvertingCodec<>("String", Variables::stringValue, v -> v instanceof String s ? s : null);

    /** Codec of {@link Long}; also reads the lossless integral types {@link Integer}, {@link Short}, {@link Byte}. */
    public static final VariableCodec<Long> LONG = new ConvertingCodec<>("Long", Variables::longValue, v -> {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        return null;
    });

    /** Codec of {@link Integer}; also reads {@link Short}, {@link Byte} and a {@link Long} within the int range. */
    public static final VariableCodec<Integer> INTEGER =
            new ConvertingCodec<>("Integer", Variables::integerValue, v -> {
                if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                    return ((Number) v).intValue();
                }
                if (v instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    return l.intValue();
                }
                return null;
            });

    /** Codec of {@link Double}; also reads {@link Float}. */
    public static final VariableCodec<Double> DOUBLE = new ConvertingCodec<>("Double", Variables::doubleValue, v -> {
        if (v instanceof Double || v instanceof Float) {
            return ((Number) v).doubleValue();
        }
        return null;
    });

    /** Codec of {@link Boolean}. */
    public static final VariableCodec<Boolean> BOOLEAN =
            new ConvertingCodec<>("Boolean", Variables::booleanValue, v -> v instanceof Boolean b ? b : null);

    /** Codec of {@link Date}. */
    public static final VariableCodec<Date> DATE =
            new ConvertingCodec<>("Date", Variables::dateValue, v -> v instanceof Date d ? d : null);

    /** Codec of {@link UUID}, stored as its string form. */
    public static final VariableCodec<UUID> UUID_CODEC = new VariableCodec<>() {
        @Override
        public String describeType() {
            return "UUID";
        }

        @Override
        public TypedValue encode(String variableName, UUID value) {
            return Variables.stringValue(value.toString());
        }

        @Override
        public UUID decode(String variableName, TypedValue value) {
            String text = stringOf(variableName, "UUID (stored as String)", value);
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new WrongVariableTypeException(
                        variableName, "UUID (stored as String)", "the String '" + text + "'", "not a UUID", e);
            }
        }
    };

    /**
     * Creates the codec of an enum type, stored as the constant's name.
     *
     * @param type the enum type
     * @param <E> the enum type
     * @return the codec
     */
    public static <E extends Enum<E>> VariableCodec<E> enumCodec(Class<E> type) {
        String expected = type.getSimpleName() + " (stored as String)";
        return new VariableCodec<>() {
            @Override
            public String describeType() {
                return expected;
            }

            @Override
            public TypedValue encode(String variableName, E value) {
                return Variables.stringValue(value.name());
            }

            @Override
            public E decode(String variableName, TypedValue value) {
                String text = stringOf(variableName, expected, value);
                try {
                    return Enum.valueOf(type, text);
                } catch (IllegalArgumentException e) {
                    throw new WrongVariableTypeException(
                            variableName,
                            expected,
                            "the String '" + text + "'",
                            "not a constant of " + type.getName(),
                            e);
                }
            }
        };
    }

    /**
     * Returns the Java value of a typed value, or {@code null} if it is an object that was not deserialized.
     *
     * @param value the typed value
     * @return the Java value
     */
    static Object javaValue(TypedValue value) {
        if (value instanceof ObjectValue objectValue && !objectValue.isDeserialized()) {
            return null;
        }
        return value.getValue();
    }

    /**
     * Describes what a typed value holds, for error messages.
     *
     * @param value the typed value
     * @return the description
     */
    static String describe(TypedValue value) {
        Object java = javaValue(value);
        String engineType =
                value.getType() == null ? "unknown" : value.getType().getName();
        if (java == null) {
            return "a serialized " + engineType + " value that is not deserialized";
        }
        return "a " + engineType + " value of Java type " + java.getClass().getName();
    }

    private static String stringOf(String variableName, String expected, TypedValue value) {
        if (javaValue(value) instanceof String text) {
            return text;
        }
        throw new WrongVariableTypeException(variableName, expected, describe(value), "");
    }

    private static final class ConvertingCodec<T> implements VariableCodec<T> {

        private final String type;
        private final Function<T, TypedValue> encoder;
        private final Function<Object, T> converter;

        ConvertingCodec(String type, Function<T, TypedValue> encoder, Function<Object, T> converter) {
            this.type = type;
            this.encoder = encoder;
            this.converter = converter;
        }

        @Override
        public String describeType() {
            return type;
        }

        @Override
        public TypedValue encode(String variableName, T value) {
            return encoder.apply(value);
        }

        @Override
        public T decode(String variableName, TypedValue value) {
            Object java = javaValue(value);
            T converted = java == null ? null : converter.apply(java);
            if (converted == null) {
                throw new WrongVariableTypeException(variableName, type, describe(value), "");
            }
            return converted;
        }
    }
}
