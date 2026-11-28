/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.internal;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.extensions.plugin.historykafka.HistoryKafkaException;

/**
 * Converts a history event into a flat map of its simple-typed bean properties (strings, numbers,
 * booleans, enums, dates as ISO-8601 instants, byte arrays as Base64). Nested objects and
 * collections are not included. Properties that carry bulk or variable data are dropped, see
 * {@link #EXCLUDED} and {@link #VARIABLE_VALUES}.
 *
 * <p>Thread-safe: the per-class property lists are cached in a concurrent map and never mutated.
 */
public final class HistoryEventMapper {

    /** Never published: internal state and bulk data (stack traces). */
    static final Set<String> EXCLUDED = Set.of("class", "persistentState", "exceptionStacktrace", "errorDetails");

    /** Dropped from variable events unless variable values are enabled. */
    static final Set<String> VARIABLE_VALUES =
            Set.of("textValue", "textValue2", "longValue", "doubleValue", "byteValue");

    private record Property(String name, Method getter) {}

    private final boolean includeVariableValues;
    private final ConcurrentMap<Class<?>, List<Property>> properties = new ConcurrentHashMap<>();

    public HistoryEventMapper(boolean includeVariableValues) {
        this.includeVariableValues = includeVariableValues;
    }

    public Map<String, Object> toData(HistoryEvent event) {
        boolean variableEvent = "variable-instance".equals(HistoryEventCategories.of(event));
        Map<String, Object> data = new LinkedHashMap<>();
        for (Property property : propertiesOf(event.getClass())) {
            if (variableEvent && !includeVariableValues && VARIABLE_VALUES.contains(property.name())) {
                continue;
            }
            Object value = read(event, property);
            if (value != null) {
                data.put(property.name(), convert(value));
            }
        }
        return data;
    }

    private static Object convert(Object value) {
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        return value;
    }

    private static Object read(HistoryEvent event, Property property) {
        try {
            return property.getter().invoke(event);
        } catch (InvocationTargetException e) {
            throw new HistoryKafkaException(
                    "Cannot read property '" + property.name() + "' of history event "
                            + event.getClass().getSimpleName() + " (id=" + event.getId() + ")",
                    e.getCause());
        } catch (IllegalAccessException e) {
            throw new HistoryKafkaException(
                    "Cannot access property '" + property.name() + "' of "
                            + event.getClass().getName(),
                    e);
        }
    }

    private List<Property> propertiesOf(Class<?> type) {
        return properties.computeIfAbsent(type, HistoryEventMapper::introspect);
    }

    private static List<Property> introspect(Class<?> type) {
        PropertyDescriptor[] descriptors;
        try {
            descriptors = Introspector.getBeanInfo(type).getPropertyDescriptors();
        } catch (IntrospectionException e) {
            throw new HistoryKafkaException("Cannot introspect history event class " + type.getName(), e);
        }
        List<Property> result = new ArrayList<>();
        for (PropertyDescriptor descriptor : descriptors) {
            Method getter = descriptor.getReadMethod();
            if (getter == null
                    || EXCLUDED.contains(descriptor.getName())
                    || !isSimple(getter.getReturnType())
                    || !java.lang.reflect.Modifier.isPublic(
                            getter.getDeclaringClass().getModifiers())) {
                continue;
            }
            result.add(new Property(descriptor.getName(), getter));
        }
        return List.copyOf(result);
    }

    private static boolean isSimple(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || Number.class.isAssignableFrom(type)
                || type == Date.class
                || type.isEnum()
                || type == byte[].class;
    }
}
