/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.internal;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * A serialized history event, ready to be handed to the producer. Immutable.
 *
 * @param type the event type, {@code <category>.<event>}
 * @param processInstanceId the process instance, for log messages; may be {@code null}
 */
public record OutgoingEvent(
        String topic, String key, String value, String contentType, String type, String processInstanceId) {

    public ProducerRecord<String, String> toRecord() {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, key, value);
        record.headers().add("content-type", contentType.getBytes(StandardCharsets.UTF_8));
        record.headers().add("operaton-event-type", type.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** Context for log and exception messages; never contains the payload. */
    public String describe() {
        return "type=" + type + ", topic=" + topic + ", key=" + key + ", processInstanceId=" + processInstanceId;
    }
}
