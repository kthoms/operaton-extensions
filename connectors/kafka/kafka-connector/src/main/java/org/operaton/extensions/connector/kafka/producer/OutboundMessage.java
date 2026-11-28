/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A record to send. Immutable.
 *
 * @param topic the target topic, not blank
 * @param partition the target partition, {@code null} to let the partitioner choose
 * @param key the record key, may be {@code null}
 * @param value the record value, may be {@code null} (a tombstone)
 * @param headers the record headers, never {@code null}
 * @param timeout how long to wait for the broker's acknowledgement, {@code null} for the sender's default
 */
public record OutboundMessage(
        String topic, Integer partition, byte[] key, byte[] value, List<MessageHeader> headers, Duration timeout) {

    public OutboundMessage {
        Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (partition != null && partition < 0) {
            throw new IllegalArgumentException("partition must not be negative: " + partition);
        }
        if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        headers = headers == null ? List.of() : List.copyOf(headers);
    }

    public static Builder builder(String topic) {
        return new Builder(topic);
    }

    /** The first header with the given name, or {@code null}. */
    public MessageHeader header(String name) {
        for (MessageHeader header : headers) {
            if (header.name().equals(name)) {
                return header;
            }
        }
        return null;
    }

    /** Builder; not thread-safe. */
    public static final class Builder {
        private final String topic;
        private Integer partition;
        private byte[] key;
        private byte[] value;
        private final List<MessageHeader> headers = new ArrayList<>();
        private Duration timeout;

        private Builder(String topic) {
            this.topic = topic;
        }

        public Builder partition(Integer partition) {
            this.partition = partition;
            return this;
        }

        public Builder key(byte[] key) {
            this.key = key;
            return this;
        }

        public Builder value(byte[] value) {
            this.value = value;
            return this;
        }

        public Builder header(MessageHeader header) {
            headers.add(Objects.requireNonNull(header, "header"));
            return this;
        }

        public Builder headers(List<MessageHeader> more) {
            more.forEach(this::header);
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public OutboundMessage build() {
            return new OutboundMessage(topic, partition, key, value, headers, timeout);
        }
    }
}
