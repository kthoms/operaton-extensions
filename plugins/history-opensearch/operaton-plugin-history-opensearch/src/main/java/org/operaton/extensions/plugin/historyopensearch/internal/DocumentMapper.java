/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.operaton.bpm.engine.impl.history.event.HistoricIncidentEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricScopeInstanceEvent;
import org.operaton.bpm.engine.impl.history.event.HistoricVariableUpdateEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchException;
import org.operaton.extensions.plugin.historyopensearch.HistoryOpenSearchOptions;

/**
 * Turns history events into {@link HistoryDocument}s: filter, document id, the fields of the event and
 * the instant that decides the index. The fields are the simple-typed bean properties of the event
 * class (strings, numbers, booleans, enums, dates as ISO-8601 instants), so properties that later
 * Operaton versions add are indexed too; internal state, stack traces and raw variable storage are
 * dropped. Thread-safe: the per-class property lists are cached in a concurrent map and never mutated.
 */
public final class DocumentMapper {

    /** Event name of the variable detail log entries, which are not part of a current-state document. */
    static final String VARIABLE_DETAIL_EVENT = "update-detail";

    private static final Set<String> EXCLUDED = Set.of(
            "class",
            "id",
            "persistentState",
            "eventType",
            "sequenceCounter",
            "exceptionStacktrace",
            "errorDetails",
            "durationRaw",
            "historyConfiguration",
            "byteArrayId",
            "byteValue",
            "textValue",
            "textValue2",
            "longValue",
            "doubleValue",
            "serializerName",
            "revisionNext",
            "userOperationId");

    private record Property(String name, Method getter) {}

    private final HistoryOpenSearchOptions options;
    private final ConcurrentMap<Class<?>, List<Property>> properties = new ConcurrentHashMap<>();

    /** Creates a mapper that applies the filter and the variable settings of the options. */
    public DocumentMapper(HistoryOpenSearchOptions options) {
        this.options = options;
    }

    /**
     * The document change for the event, or {@code null} when the event is not indexed (unsupported
     * kind, variable detail entry, or removed by the {@code eventTypes} filter).
     *
     * @param event the history event, read now (it may change later in the transaction)
     * @param now the current time, used as {@code updatedAt} and as the index instant of creation events
     */
    public HistoryDocument map(HistoryEvent event, Instant now) {
        String category = HistoryEventCategories.of(event);
        String eventName = event.getEventType();
        if (category == null || VARIABLE_DETAIL_EVENT.equals(eventName) || !options.accepts(category, eventName)) {
            return null;
        }
        String id = documentId(category, event);
        if (id == null) {
            throw new HistoryOpenSearchException("History event " + category + "." + eventName
                    + " has no id to build a document from (processInstanceId=" + event.getProcessInstanceId() + ")");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Property property : propertiesOf(event.getClass())) {
            if (EXCLUDED.contains(property.name())) {
                continue;
            }
            Object value = read(event, property);
            if (value != null) {
                fields.put(property.name(), convert(value));
            }
        }
        if (event instanceof HistoricVariableUpdateEventEntity variable) {
            variableFields(variable, fields);
        }
        if (eventName != null) {
            fields.put("lastEvent", eventName);
        }
        fields.put("updatedAt", now.toString());
        return new HistoryDocument(
                category, id, fields, anchor(event, eventName, now), eventName, event.getProcessInstanceId());
    }

    private static String documentId(String category, HistoryEvent event) {
        return switch (category) {
            case HistoryEventCategories.PROCESS_INSTANCE -> firstNonNull(event.getProcessInstanceId(), event.getId());
            case HistoryEventCategories.VARIABLE_INSTANCE ->
                ((HistoricVariableUpdateEventEntity) event).getVariableInstanceId();
            case HistoryEventCategories.ACTIVITY_INSTANCE ->
                firstNonNull(
                        ((org.operaton.bpm.engine.impl.history.event.HistoricActivityInstanceEventEntity) event)
                                .getActivityInstanceId(),
                        event.getId());
            case HistoryEventCategories.TASK_INSTANCE ->
                firstNonNull(
                        ((org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity) event)
                                .getTaskId(),
                        event.getId());
            default -> event.getId();
        };
    }

    /**
     * The instant that places a not yet known document into a time-based index: when the entity began,
     * if the event says so. Scope events carry the start time on creation and, when the database history
     * handler runs first, on later events too; variable events only say when they happened, which is the
     * start only for the creation event.
     */
    private static Instant anchor(HistoryEvent event, String eventName, Instant now) {
        Date began = null;
        if (event instanceof HistoricScopeInstanceEvent scope) {
            began = scope.getStartTime();
        } else if (event instanceof HistoricIncidentEventEntity incident) {
            began = incident.getCreateTime();
        } else if (event instanceof HistoricVariableUpdateEventEntity variable && "create".equals(eventName)) {
            began = variable.getTimestamp();
        }
        if (began != null) {
            return began.toInstant();
        }
        boolean creation = "start".equals(eventName) || "create".equals(eventName);
        return creation ? now : null;
    }

    private void variableFields(HistoricVariableUpdateEventEntity variable, Map<String, Object> fields) {
        String type = variable.getSerializerName();
        if (type != null) {
            fields.put("variableType", type);
        }
        fields.put("state", "delete".equals(variable.getEventType()) ? "DELETED" : "CREATED");
        if (!options.includeVariableValues()) {
            return;
        }
        Long longValue = variable.getLongValue();
        Double doubleValue = variable.getDoubleValue();
        String text = variable.getTextValue();
        String normalized = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "null" -> {}
            case "integer", "long", "short" -> putIfPresent(fields, "valueLong", longValue);
            case "double" -> putIfPresent(fields, "valueDouble", doubleValue);
            case "boolean" -> {
                if (longValue != null) {
                    fields.put("valueBoolean", longValue != 0);
                }
            }
            case "date" -> {
                if (longValue != null) {
                    fields.put("valueDate", Instant.ofEpochMilli(longValue).toString());
                }
            }
            default -> {
                if (text != null) {
                    int max = options.maxVariableValueLength();
                    if (text.length() > max) {
                        fields.put("valueText", text.substring(0, max));
                        fields.put("valueTruncated", true);
                    } else {
                        fields.put("valueText", text);
                    }
                }
            }
        }
    }

    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
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
            throw new HistoryOpenSearchException(
                    "Cannot read property '" + property.name() + "' of history event "
                            + event.getClass().getSimpleName() + " (id=" + event.getId() + ")",
                    e.getCause());
        } catch (IllegalAccessException e) {
            throw new HistoryOpenSearchException(
                    "Cannot access property '" + property.name() + "' of "
                            + event.getClass().getName(),
                    e);
        }
    }

    private List<Property> propertiesOf(Class<?> type) {
        return properties.computeIfAbsent(type, DocumentMapper::introspect);
    }

    private static List<Property> introspect(Class<?> type) {
        PropertyDescriptor[] descriptors;
        try {
            descriptors = Introspector.getBeanInfo(type).getPropertyDescriptors();
        } catch (IntrospectionException e) {
            throw new HistoryOpenSearchException("Cannot introspect history event class " + type.getName(), e);
        }
        List<Property> result = new ArrayList<>();
        for (PropertyDescriptor descriptor : descriptors) {
            Method getter = descriptor.getReadMethod();
            if (getter == null
                    || !isSimple(getter.getReturnType())
                    || !Modifier.isPublic(getter.getDeclaringClass().getModifiers())) {
                continue;
            }
            result.add(new Property(descriptor.getName(), getter));
        }
        return List.copyOf(result);
    }

    private static boolean isSimple(Class<?> type) {
        return (type.isPrimitive() && type != byte.class)
                || type == String.class
                || type == Boolean.class
                || Number.class.isAssignableFrom(type)
                || type == Date.class
                || type.isEnum();
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
