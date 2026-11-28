/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.operaton.extensions.connector.kafka.internal.Values;
import org.operaton.extensions.connector.kafka.producer.MessageHeader;

/**
 * A decoded Kafka record, the input of the mappings. Key and value are UTF-8 text. Not thread-safe (one record is
 * handled by one thread).
 */
public final class InboundRecord {

    private final String topic;
    private final int partition;
    private final long offset;
    private final long timestamp;
    private final String key;
    private final String value;
    private final List<MessageHeader> headers;
    private JsonNode json;

    public InboundRecord(
            String topic,
            int partition,
            long offset,
            long timestamp,
            String key,
            String value,
            List<MessageHeader> headers) {
        this.topic = topic;
        this.partition = partition;
        this.offset = offset;
        this.timestamp = timestamp;
        this.key = key;
        this.value = value;
        this.headers = List.copyOf(headers);
    }

    public String topic() {
        return topic;
    }

    public int partition() {
        return partition;
    }

    public long offset() {
        return offset;
    }

    public long timestamp() {
        return timestamp;
    }

    public String key() {
        return key;
    }

    public String value() {
        return value;
    }

    public List<MessageHeader> headers() {
        return headers;
    }

    /** The last header with the name (Kafka semantics), as text; {@code null} when absent. */
    public String header(String name) {
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i).name().equals(name)) {
                return headers.get(i).textValue();
            }
        }
        return null;
    }

    /** {@code topic-partition@offset}, for messages. */
    public String coordinates() {
        return topic + "-" + partition + "@" + offset;
    }

    /**
     * The value parsed as JSON (once).
     *
     * @throws MappingException when the value is absent or not JSON
     */
    JsonNode json() {
        if (json == null) {
            if (value == null) {
                throw new MappingException("record %s has no value (tombstone)".formatted(coordinates()));
            }
            try {
                json = Values.MAPPER.readTree(value);
            } catch (JsonProcessingException e) {
                throw new MappingException(
                        "record %s: the value is not valid JSON: %s".formatted(coordinates(), e.getOriginalMessage()),
                        e);
            }
            if (json == null) {
                throw new MappingException("record %s: the value is empty, not JSON".formatted(coordinates()));
            }
        }
        return json;
    }
}
