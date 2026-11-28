/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Conversions between process values, text and UTF-8 bytes. Not API. */
public final class Values {

    /** Thread-safe once configured; never reconfigured. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Values() {}

    /**
     * Text form of a process value: strings, numbers, booleans and enums as their text, byte arrays as UTF-8, Spin
     * values through their own {@code toString()} (JSON/XML text), everything else (maps, lists, beans) as JSON.
     *
     * @return the text, {@code null} for {@code null}
     * @throws IllegalArgumentException when the value cannot be written as JSON
     */
    public static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return decodeUtf8(bytes);
        }
        if (value.getClass().getName().startsWith("org.operaton.spin.")) {
            return value.toString();
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "cannot write a value of type %s as JSON: %s"
                            .formatted(value.getClass().getName(), e.getOriginalMessage()),
                    e);
        }
    }

    public static byte[] utf8(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Strict UTF-8 decoding.
     *
     * @throws IllegalArgumentException when the bytes are not valid UTF-8
     */
    public static String decodeUtf8(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not valid UTF-8 text", e);
        }
    }
}
