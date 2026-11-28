/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.impl.value.FileValueImpl;
import org.operaton.bpm.engine.variable.value.BooleanValue;
import org.operaton.bpm.engine.variable.value.BytesValue;
import org.operaton.bpm.engine.variable.value.DateValue;
import org.operaton.bpm.engine.variable.value.DoubleValue;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.bpm.engine.variable.value.IntegerValue;
import org.operaton.bpm.engine.variable.value.LongValue;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.bpm.engine.variable.value.ShortValue;
import org.operaton.bpm.engine.variable.value.StringValue;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.variableencryption.internal.MarkedValues;
import org.operaton.extensions.plugin.variableencryption.internal.SpinTagging;

/**
 * Tags a value so that it is stored encrypted regardless of the variable's name (the plugin must be
 * registered, with {@code encryptMarkedValues} left on):
 *
 * <pre>{@code
 * runtimeService.setVariable(id, "passportNumber", EncryptedVariables.encrypted("X1234567"));
 * variables.putValueTyped("iban", EncryptedVariables.encrypted(Variables.stringValue(iban)));
 * }</pre>
 *
 * The tag applies to <em>this write</em> only. A later {@code setVariable} of the same variable
 * without the tag, under a name that no configured pattern covers, stores plaintext. Use name
 * patterns for variables that must always be encrypted.
 *
 * <p>Supported: String, Boolean, Short, Integer, Long, Double, Date, byte[], File, Object values
 * and, when the Spin plugin is present, JSON and XML values.
 */
public final class EncryptedVariables {

    private EncryptedVariables() {}

    /**
     * Tags a typed value. A tagged value is returned unchanged.
     *
     * @param value a typed value of one of the supported kinds
     * @return an equivalent value that is encrypted when stored
     * @throws IllegalArgumentException if the value is {@code null} or of an unsupported kind (use a
     *     variable name pattern for those)
     */
    public static TypedValue encrypted(TypedValue value) {
        if (value == null) {
            throw new IllegalArgumentException("A null value cannot be tagged for encryption");
        }
        if (value instanceof EncryptedValue) {
            return value;
        }
        TypedValue tagged = tag(value);
        if (tagged == null) {
            throw new IllegalArgumentException(
                    "Values of type " + value.getClass().getName()
                            + " cannot be tagged for encryption; designate the variable by a name pattern instead");
        }
        return tagged;
    }

    /**
     * Tags a plain value: strings, booleans, numbers, dates and byte arrays become the matching
     * primitive typed value, anything else an {@link ObjectValue} (serialized with the engine's
     * default format; build the {@code ObjectValue} yourself to choose JSON).
     *
     * @param value the value to protect
     * @return a typed value that is encrypted when stored
     * @throws IllegalArgumentException if the value is {@code null}
     */
    public static TypedValue encrypted(Object value) {
        if (value instanceof TypedValue typed) {
            return encrypted(typed);
        }
        if (value == null) {
            throw new IllegalArgumentException("A null value cannot be tagged for encryption");
        }
        if (value instanceof String string) {
            return new MarkedValues.MarkedString(string);
        } else if (value instanceof Boolean bool) {
            return new MarkedValues.MarkedBoolean(bool);
        } else if (value instanceof Short number) {
            return new MarkedValues.MarkedShort(number);
        } else if (value instanceof Integer number) {
            return new MarkedValues.MarkedInteger(number);
        } else if (value instanceof Long number) {
            return new MarkedValues.MarkedLong(number);
        } else if (value instanceof Double number) {
            return new MarkedValues.MarkedDouble(number);
        } else if (value instanceof Date date) {
            return new MarkedValues.MarkedDate(date);
        } else if (value instanceof byte[] bytes) {
            return new MarkedValues.MarkedBytes(bytes);
        }
        return encrypted(Variables.objectValue(value).create());
    }

    /**
     * Whether the value is tagged.
     *
     * @param value any typed value
     * @return {@code true} for a value created by {@code encrypted(...)}
     */
    public static boolean isEncrypted(TypedValue value) {
        return value instanceof EncryptedValue;
    }

    private static TypedValue tag(TypedValue value) {
        boolean transientValue = value.isTransient();
        TypedValue tagged = null;
        if (value instanceof StringValue v) {
            tagged = new MarkedValues.MarkedString(v.getValue(), transientValue);
        } else if (value instanceof BooleanValue v) {
            tagged = new MarkedValues.MarkedBoolean(v.getValue(), transientValue);
        } else if (value instanceof ShortValue v) {
            tagged = new MarkedValues.MarkedShort(v.getValue(), transientValue);
        } else if (value instanceof IntegerValue v) {
            tagged = new MarkedValues.MarkedInteger(v.getValue(), transientValue);
        } else if (value instanceof LongValue v) {
            tagged = new MarkedValues.MarkedLong(v.getValue(), transientValue);
        } else if (value instanceof DoubleValue v) {
            tagged = new MarkedValues.MarkedDouble(v.getValue(), transientValue);
        } else if (value instanceof DateValue v) {
            tagged = new MarkedValues.MarkedDate(v.getValue(), transientValue);
        } else if (value instanceof BytesValue v) {
            tagged = new MarkedValues.MarkedBytes(v.getValue(), transientValue);
        } else if (value instanceof FileValue v) {
            tagged = new MarkedValues.MarkedFile(fileBytes(v), v);
        } else if (value instanceof ObjectValue v) {
            tagged = new MarkedValues.MarkedObject(v);
        } else if (SpinTagging.available()) {
            tagged = SpinTagging.tag(value);
        }
        return tagged;
    }

    private static byte[] fileBytes(FileValue file) {
        if (file instanceof FileValueImpl impl) {
            return impl.getByteArray();
        }
        try (InputStream in = file.getValue()) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read the content of file value '" + file.getFilename() + "'", e);
        }
    }
}
