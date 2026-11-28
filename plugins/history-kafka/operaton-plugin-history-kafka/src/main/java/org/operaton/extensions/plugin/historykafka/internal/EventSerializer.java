/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.extensions.plugin.historykafka.HistoryKafkaException;
import org.operaton.extensions.plugin.historykafka.HistoryKafkaOptions;
import org.operaton.extensions.plugin.historykafka.PayloadFormat;

/** Turns history events into {@link OutgoingEvent}s: filter, topic, key and JSON / CloudEvents payload. Thread-safe. */
public final class EventSerializer {

    static final String CLOUDEVENTS_TYPE_PREFIX = "org.operaton.history.";

    private final HistoryKafkaOptions options;
    private final HistoryEventMapper mapper;
    private final ObjectWriter writer = new ObjectMapper().writer();

    public EventSerializer(HistoryKafkaOptions options) {
        this.options = options;
        this.mapper = new HistoryEventMapper(options.includeVariableValues());
    }

    /** The serialized event, or {@code null} when the event is filtered out by the {@code eventTypes} filter. */
    public OutgoingEvent serialize(HistoryEvent event, Instant now) {
        String category = HistoryEventCategories.of(event);
        String eventName = event.getEventType();
        if (!options.accepts(category, eventName)) {
            return null;
        }
        String type = eventName == null ? category : category + "." + eventName;
        String key = firstNonNull(event.getProcessInstanceId(), event.getRootProcessInstanceId(), event.getId());
        Map<String, Object> envelope = new LinkedHashMap<>();
        String contentType;
        if (options.format() == PayloadFormat.CLOUDEVENTS) {
            contentType = "application/cloudevents+json";
            envelope.put("specversion", "1.0");
            envelope.put("id", UUID.randomUUID().toString());
            envelope.put("source", options.source());
            envelope.put("type", CLOUDEVENTS_TYPE_PREFIX + type);
            envelope.put("time", now.toString());
            envelope.put("datacontenttype", "application/json");
            if (key != null) {
                envelope.put("subject", key);
            }
        } else {
            contentType = "application/json";
            envelope.put("id", UUID.randomUUID().toString());
            envelope.put("type", type);
            envelope.put("category", category);
            if (eventName != null) {
                envelope.put("event", eventName);
            }
            envelope.put("time", now.toString());
            envelope.put("source", options.source());
        }
        envelope.put("data", mapper.toData(event));
        try {
            return new OutgoingEvent(
                    options.topicFor(category, eventName),
                    key,
                    writer.writeValueAsString(envelope),
                    contentType,
                    type,
                    event.getProcessInstanceId());
        } catch (JsonProcessingException e) {
            throw new HistoryKafkaException(
                    "Cannot serialize history event " + type + " (id=" + event.getId() + ", processInstanceId="
                            + event.getProcessInstanceId() + ")",
                    e);
        }
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
