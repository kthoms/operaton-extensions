/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

/**
 * An in-memory {@link ValueFields}: what a serializer writes is captured here, turned into the
 * payload that is encrypted, and on the way back filled from the decrypted payload for the
 * serializer to read. Not API; single-threaded use only.
 *
 * <p>Payload layout: version byte (1), the serializer name, then the five fields in the order text,
 * text2, long, double, bytes, each preceded by a presence marker. Strings and byte arrays are length
 * prefixed (UTF-8), so the length of a string is not limited by the 4000 characters of the text column.
 */
final class FieldSnapshot implements ValueFields {

    private static final int PAYLOAD_VERSION = 1;

    private final String name;
    private String textValue;
    private String textValue2;
    private Long longValue;
    private Double doubleValue;
    private byte[] byteArrayValue;

    FieldSnapshot(String name) {
        this.name = name;
    }

    /** Serializes the fields, tagged with the name of the serializer that wrote them. */
    byte[] toPayload(String serializerName) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(PAYLOAD_VERSION);
            writeBytes(out, serializerName.getBytes(StandardCharsets.UTF_8));
            writeBytes(out, textValue == null ? null : textValue.getBytes(StandardCharsets.UTF_8));
            writeBytes(out, textValue2 == null ? null : textValue2.getBytes(StandardCharsets.UTF_8));
            out.writeBoolean(longValue != null);
            if (longValue != null) {
                out.writeLong(longValue);
            }
            out.writeBoolean(doubleValue != null);
            if (doubleValue != null) {
                out.writeDouble(doubleValue);
            }
            writeBytes(out, byteArrayValue);
        } catch (IOException e) {
            throw new VariableEncryptionException("Cannot serialize variable '" + name + "' for encryption", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Restores the fields from a decrypted payload.
     *
     * @param payload the decrypted payload
     * @param name the variable name, for messages
     * @param expectedSerializer the serializer that is going to read the fields
     * @return the fields
     * @throws VariableEncryptionException if the payload is malformed or written by another serializer
     */
    static FieldSnapshot fromPayload(byte[] payload, String name, String expectedSerializer) {
        FieldSnapshot snapshot = new FieldSnapshot(name);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = in.readUnsignedByte();
            if (version != PAYLOAD_VERSION) {
                throw new VariableEncryptionException("Variable '" + name + "' has payload version " + version
                        + "; this plugin reads version " + PAYLOAD_VERSION + " (upgrade the plugin)");
            }
            String serializer = new String(readBytes(in), StandardCharsets.UTF_8);
            if (!serializer.equals(expectedSerializer)) {
                throw new VariableEncryptionException("Variable '" + name + "' was encrypted from a value of type '"
                        + serializer + "' but is read as '" + expectedSerializer + "'");
            }
            byte[] text = readBytes(in);
            snapshot.textValue = text == null ? null : new String(text, StandardCharsets.UTF_8);
            byte[] text2 = readBytes(in);
            snapshot.textValue2 = text2 == null ? null : new String(text2, StandardCharsets.UTF_8);
            snapshot.longValue = in.readBoolean() ? in.readLong() : null;
            snapshot.doubleValue = in.readBoolean() ? in.readDouble() : null;
            snapshot.byteArrayValue = readBytes(in);
        } catch (IOException e) {
            throw new VariableEncryptionException(
                    "Variable '" + name + "' has a malformed encrypted payload (tampered data?)", e);
        }
        return snapshot;
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(value.length);
            out.write(value);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > in.available()) {
            throw new IOException("length " + length + " exceeds the payload");
        }
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getTextValue() {
        return textValue;
    }

    @Override
    public void setTextValue(String textValue) {
        this.textValue = textValue;
    }

    @Override
    public String getTextValue2() {
        return textValue2;
    }

    @Override
    public void setTextValue2(String textValue2) {
        this.textValue2 = textValue2;
    }

    @Override
    public Long getLongValue() {
        return longValue;
    }

    @Override
    public void setLongValue(Long longValue) {
        this.longValue = longValue;
    }

    @Override
    public Double getDoubleValue() {
        return doubleValue;
    }

    @Override
    public void setDoubleValue(Double doubleValue) {
        this.doubleValue = doubleValue;
    }

    @Override
    public byte[] getByteArrayValue() {
        return byteArrayValue;
    }

    @Override
    public void setByteArrayValue(byte[] bytes) {
        this.byteArrayValue = bytes;
    }
}
