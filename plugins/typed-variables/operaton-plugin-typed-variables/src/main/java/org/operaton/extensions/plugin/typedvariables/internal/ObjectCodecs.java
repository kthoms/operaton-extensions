/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.internal;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.typedvariables.SerializationFormat;
import org.operaton.extensions.plugin.typedvariables.VariableCodec;
import org.operaton.extensions.plugin.typedvariables.WrongVariableTypeException;

/**
 * The codecs of objects, lists and sets: stored as an object value in JSON (Spin) or Java serialization.
 *
 * <p>JSON is chosen by {@link SerializationFormat#AUTO} when the Spin engine plugin and its Jackson data format are
 * on the classpath. The engine still needs the Spin plugin registered (Spring Boot does it automatically); otherwise
 * writing fails with the engine's own message.
 */
public final class ObjectCodecs {

    private static final String SPIN_PLUGIN = "org.operaton.spin.plugin.impl.SpinProcessEnginePlugin";
    private static final String SPIN_JSON = "org.operaton.spin.impl.json.jackson.format.JacksonJsonDataFormat";

    private ObjectCodecs() {}

    /**
     * Tells whether JSON storage through Spin is available on the classpath.
     *
     * @return {@code true} if the Spin engine plugin and its Jackson JSON data format are present
     */
    public static boolean jsonAvailable() {
        return isPresent(SPIN_PLUGIN) && isPresent(SPIN_JSON);
    }

    /**
     * Creates the codec of a single object.
     *
     * @param type the class of the object
     * @param format the requested format
     * @param <T> the object type
     * @return the codec
     */
    public static <T> VariableCodec<T> object(Class<T> type, SerializationFormat format) {
        Objects.requireNonNull(type, "type");
        String dataFormat = resolve(format);
        requireSerializable(dataFormat, type);
        return new VariableCodec<>() {
            @Override
            public String describeType() {
                return type.getSimpleName();
            }

            @Override
            public TypedValue encode(String variableName, T value) {
                if (!type.isInstance(value)) {
                    throw new WrongVariableTypeException(
                            variableName,
                            type.getName(),
                            "a value of Java type " + value.getClass().getName(),
                            "");
                }
                return Variables.objectValue(value)
                        .serializationDataFormat(dataFormat)
                        .create();
            }

            @Override
            public T decode(String variableName, TypedValue value) {
                Object java = ValueCodecs.javaValue(value);
                if (type.isInstance(java)) {
                    return type.cast(java);
                }
                throw new WrongVariableTypeException(
                        variableName, type.getName(), ValueCodecs.describe(value), hint(java));
            }
        };
    }

    /**
     * Creates the codec of a list.
     *
     * @param elementType the class of the elements
     * @param format the requested format
     * @param <E> the element type
     * @return the codec
     */
    public static <E> VariableCodec<List<E>> list(Class<E> elementType, SerializationFormat format) {
        return collection("List", List.class, elementType, format, ArrayList::new);
    }

    /**
     * Creates the codec of a set; iteration order is the insertion order.
     *
     * @param elementType the class of the elements
     * @param format the requested format
     * @param <E> the element type
     * @return the codec
     */
    public static <E> VariableCodec<Set<E>> set(Class<E> elementType, SerializationFormat format) {
        return collection("Set", Set.class, elementType, format, LinkedHashSet::new);
    }

    private static <E, C extends Collection<E>> VariableCodec<C> collection(
            String kind,
            Class<?> collectionType,
            Class<E> elementType,
            SerializationFormat format,
            Supplier<C> factory) {
        Objects.requireNonNull(elementType, "elementType");
        String dataFormat = resolve(format);
        requireSerializable(dataFormat, elementType);
        String expected = kind + "<" + elementType.getSimpleName() + ">";
        return new VariableCodec<>() {
            @Override
            public String describeType() {
                return expected;
            }

            @Override
            public TypedValue encode(String variableName, C value) {
                C copy = factory.get();
                for (E element : value) {
                    if (!elementType.isInstance(element)) {
                        throw new WrongVariableTypeException(
                                variableName,
                                expected,
                                "an element of Java type "
                                        + (element == null
                                                ? "null"
                                                : element.getClass().getName()),
                                element == null ? "null elements cannot be stored" : "");
                    }
                    copy.add(element);
                }
                return Variables.objectValue(copy)
                        .serializationDataFormat(dataFormat)
                        .create();
            }

            @Override
            public C decode(String variableName, TypedValue value) {
                Object java = ValueCodecs.javaValue(value);
                if (!collectionType.isInstance(java)) {
                    throw new WrongVariableTypeException(
                            variableName, expected, ValueCodecs.describe(value), hint(java));
                }
                C copy = factory.get();
                int index = 0;
                for (Object element : (Collection<?>) java) {
                    if (!elementType.isInstance(element)) {
                        throw new WrongVariableTypeException(
                                variableName,
                                expected,
                                "an element " + index + " of Java type "
                                        + (element == null
                                                ? "null"
                                                : element.getClass().getName()),
                                element == null ? "" : hint(element));
                    }
                    copy.add(elementType.cast(element));
                    index++;
                }
                return copy;
            }
        };
    }

    private static String resolve(SerializationFormat format) {
        Objects.requireNonNull(format, "format");
        return switch (format) {
            case AUTO ->
                jsonAvailable()
                        ? Variables.SerializationDataFormats.JSON.getName()
                        : Variables.SerializationDataFormats.JAVA.getName();
            case JSON -> {
                if (!jsonAvailable()) {
                    throw new IllegalStateException(
                            "SerializationFormat.JSON needs operaton-engine-plugin-spin and operaton-spin-dataformat-json-jackson"
                                    + " on the classpath (and the Spin plugin registered in the engine)");
                }
                yield Variables.SerializationDataFormats.JSON.getName();
            }
            case JAVA -> Variables.SerializationDataFormats.JAVA.getName();
        };
    }

    private static void requireSerializable(String dataFormat, Class<?> type) {
        boolean java = Variables.SerializationDataFormats.JAVA.getName().equals(dataFormat);
        if (java && !Serializable.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(type.getName()
                    + " is not Serializable, so it cannot be stored with Java serialization;"
                    + " add operaton-engine-plugin-spin and operaton-spin-dataformat-json-jackson to store it as JSON,"
                    + " or make the class Serializable");
        }
    }

    private static String hint(Object java) {
        if (java == null) {
            return "the object was not deserialized; check that its class is on the classpath of this application";
        }
        if (java instanceof java.util.Map) {
            return "a JSON object without type information was read as a Map; write it through this declaration"
                    + " (the stored type name is needed to rebuild the class)";
        }
        return "";
    }

    private static boolean isPresent(String className) {
        return canLoad(className, ObjectCodecs.class.getClassLoader())
                || canLoad(className, Thread.currentThread().getContextClassLoader());
    }

    private static boolean canLoad(String className, ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException e) {
            // the answer to the probe, not a failure: the optional library is not on this class loader
            return false;
        }
    }
}
