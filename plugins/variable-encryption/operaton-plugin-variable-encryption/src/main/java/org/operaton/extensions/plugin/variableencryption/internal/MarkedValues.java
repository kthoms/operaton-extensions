/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.io.Serial;
import java.util.Date;
import org.operaton.bpm.engine.variable.impl.value.FileValueImpl;
import org.operaton.bpm.engine.variable.impl.value.ObjectValueImpl;
import org.operaton.bpm.engine.variable.impl.value.PrimitiveTypeValueImpl;
import org.operaton.bpm.engine.variable.type.FileValueType;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.operaton.bpm.engine.variable.value.FileValue;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.plugin.variableencryption.EncryptedValue;

/**
 * Typed values of the engine's own implementation classes with the {@link EncryptedValue} marker.
 * They are subclasses, so the engine selects its standard serializer for them. Not API: create
 * them with {@code EncryptedVariables.encrypted(...)}.
 */
public final class MarkedValues {

    private MarkedValues() {}

    /** A tagged string. */
    public static final class MarkedString extends PrimitiveTypeValueImpl.StringValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedString(String value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedString(String value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged boolean. */
    public static final class MarkedBoolean extends PrimitiveTypeValueImpl.BooleanValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedBoolean(Boolean value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedBoolean(Boolean value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged short. */
    public static final class MarkedShort extends PrimitiveTypeValueImpl.ShortValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedShort(Short value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedShort(Short value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged integer. */
    public static final class MarkedInteger extends PrimitiveTypeValueImpl.IntegerValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedInteger(Integer value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedInteger(Integer value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged long. */
    public static final class MarkedLong extends PrimitiveTypeValueImpl.LongValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedLong(Long value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedLong(Long value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged double. */
    public static final class MarkedDouble extends PrimitiveTypeValueImpl.DoubleValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedDouble(Double value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedDouble(Double value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged date. */
    public static final class MarkedDate extends PrimitiveTypeValueImpl.DateValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedDate(Date value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedDate(Date value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged byte array. */
    public static final class MarkedBytes extends PrimitiveTypeValueImpl.BytesValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates the value. */
        public MarkedBytes(byte[] value) {
            super(value);
        }

        /** Creates the value. */
        public MarkedBytes(byte[] value, boolean isTransient) {
            super(value, isTransient);
        }
    }

    /** A tagged file. */
    public static final class MarkedFile extends FileValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates a tagged copy of a file value with the given content. */
        public MarkedFile(byte[] content, FileValue source) {
            super(
                    content,
                    (FileValueType) ValueType.FILE,
                    source.getFilename(),
                    source.getMimeType(),
                    source.getEncoding());
            this.isTransient = source.isTransient();
        }
    }

    /** A tagged object value. */
    public static final class MarkedObject extends ObjectValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        /** Creates a tagged copy of an object value. */
        public MarkedObject(ObjectValue source) {
            super(
                    source.isDeserialized() ? source.getValue() : null,
                    source.getValueSerialized(),
                    source.getSerializationDataFormat(),
                    source.getObjectTypeName(),
                    source.isDeserialized());
            this.isTransient = source.isTransient();
        }
    }
}
