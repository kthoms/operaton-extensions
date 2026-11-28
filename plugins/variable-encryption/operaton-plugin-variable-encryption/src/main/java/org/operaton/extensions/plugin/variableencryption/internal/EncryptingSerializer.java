/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.util.Arrays;
import java.util.Objects;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.ValueFields;
import org.operaton.bpm.engine.variable.impl.value.UntypedValueImpl;
import org.operaton.bpm.engine.variable.type.ValueType;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.variableencryption.EncryptedValue;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

/**
 * Wraps one of the engine's serializers. It has the same name and type, so selection, stored
 * serializer names and type reporting are unchanged. For a designated variable it lets the
 * wrapped serializer write into a scratch record, encrypts that record and stores the envelope
 * in the byte array of the variable (the text column gets a marker with the key id, the other
 * columns stay empty). On reading, a marked row is decrypted and handed to the wrapped serializer;
 * every other row is read by the wrapped serializer as before.
 *
 * <p>Why this works for history: the engine copies the raw columns of a variable row into history
 * events and from there into the history tables, so history holds the same ciphertext.
 *
 * <p>Thread-safe: no mutable state except the synchronized memo. Not API.
 */
final class EncryptingSerializer<T extends TypedValue> implements TypedValueSerializer<T> {

    private final TypedValueSerializer<T> delegate;
    private final VariableEncryptor encryptor;

    EncryptingSerializer(TypedValueSerializer<T> delegate, VariableEncryptor encryptor) {
        this.delegate = delegate;
        this.encryptor = encryptor;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public ValueType getType() {
        return delegate.getType();
    }

    @Override
    public boolean canHandle(TypedValue value) {
        return delegate.canHandle(value);
    }

    @Override
    public T convertToTypedValue(UntypedValueImpl untypedValue) {
        return delegate.convertToTypedValue(untypedValue);
    }

    @Override
    public String getSerializationDataformat() {
        return delegate.getSerializationDataformat();
    }

    @Override
    public boolean isMutableValue(T typedValue) {
        return delegate.isMutableValue(typedValue);
    }

    @Override
    public void writeValue(T value, ValueFields valueFields) {
        String name = valueFields.getName();
        boolean scratchTarget = name == null; // the engine's change detection writes into a nameless scratch record
        ValueMemo.Entry remembered = encryptor.memo().get(value);

        if (!isDesignated(value, name, scratchTarget, remembered)) {
            delegate.writeValue(value, valueFields);
            return;
        }

        String aadName = name != null ? name : remembered != null ? remembered.variableName() : null;
        FieldSnapshot snapshot = new FieldSnapshot(aadName);
        delegate.writeValue(value, snapshot);
        byte[] payload = snapshot.toPayload(delegate.getName());
        boolean mutable = delegate.isMutableValue(value);
        byte[] digest = mutable ? VariableEncryptor.sha256(payload) : null;

        byte[] envelope;
        String keyId;
        if (mutable
                && remembered != null
                && Arrays.equals(remembered.payloadDigest(), digest)
                && Objects.equals(remembered.variableName(), aadName)
                && (scratchTarget
                        || remembered.keyId().equals(encryptor.cipher().activeKeyId()))) {
            // unchanged value: reproduce the stored envelope so that the engine sees no modification
            envelope = remembered.envelope();
            keyId = remembered.keyId();
        } else {
            EnvelopeCipher.Sealed sealed = encryptor.cipher().seal(payload, aadName);
            envelope = sealed.envelope();
            keyId = sealed.keyId();
            if (mutable && !scratchTarget) {
                encryptor.memo().put(value, new ValueMemo.Entry(aadName, digest, envelope, keyId));
            }
        }

        valueFields.setTextValue(EnvelopeCipher.textMarker(keyId));
        valueFields.setTextValue2(null);
        valueFields.setLongValue(null);
        valueFields.setDoubleValue(null);
        valueFields.setByteArrayValue(envelope.clone());
        if (valueFields.getByteArrayValue() == null) {
            throw new VariableEncryptionException("Cannot store the encrypted value of variable '" + name
                    + "': the target has no byte array. Variables designated for encryption cannot be used in"
                    + " queries that compare their value (the stored value is ciphertext)");
        }
    }

    private boolean isDesignated(T value, String name, boolean scratchTarget, ValueMemo.Entry remembered) {
        if (value.isTransient()) {
            return false; // never stored
        }
        boolean marked = encryptor.encryptMarkedValues() && value instanceof EncryptedValue;
        if (scratchTarget) {
            return remembered != null || marked;
        }
        return marked || encryptor.isDesignated(name);
    }

    @Override
    public T readValue(ValueFields valueFields, boolean deserializeValue, boolean isTransient) {
        String name = valueFields.getName();
        String marker = valueFields.getTextValue();
        if (!EnvelopeCipher.isMarker(marker)) {
            if (!encryptor.allowPlaintextRead() && encryptor.isDesignated(name)) {
                throw new VariableEncryptionException("Variable '" + name
                        + "' is designated for encryption but is stored in plaintext; refusing to read it."
                        + " Re-set the variable to encrypt it, or allow plaintext reads while migrating existing data");
            }
            return delegate.readValue(valueFields, deserializeValue, isTransient);
        }

        byte[] envelope = valueFields.getByteArrayValue();
        String envelopeKeyId = EnvelopeCipher.keyIdOf(envelope);
        if (envelopeKeyId != null && !marker.equals(EnvelopeCipher.textMarker(envelopeKeyId))) {
            throw new VariableEncryptionException("Variable '" + name
                    + "': the key id in the text column does not match the one in the stored envelope"
                    + " (tampered data?)");
        }
        byte[] payload = encryptor.cipher().open(envelope, name);
        FieldSnapshot snapshot = FieldSnapshot.fromPayload(payload, name, delegate.getName());
        T value = delegate.readValue(snapshot, deserializeValue, isTransient);
        if (value != null && delegate.isMutableValue(value)) {
            encryptor
                    .memo()
                    .put(
                            value,
                            new ValueMemo.Entry(
                                    name, VariableEncryptor.sha256(payload), envelope.clone(), envelopeKeyId));
        }
        return value;
    }
}
