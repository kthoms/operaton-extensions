/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A Kafka record header. Names are not unique in Kafka; the value may be {@code null}.
 *
 * @param name the header name, not blank
 * @param value the header value, may be {@code null}
 */
public record MessageHeader(String name, byte[] value) {

    public MessageHeader {
        Objects.requireNonNull(name, "header name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("header name must not be blank");
        }
    }

    public static MessageHeader text(String name, String value) {
        return new MessageHeader(name, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    /** The value as UTF-8 text, {@code null} when there is no value. */
    public String textValue() {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
