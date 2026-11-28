/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.VariableSerializerFactory;
import org.operaton.bpm.engine.variable.value.TypedValue;

/** Wraps what the engine's fallback factory (the Spin plugin's) returns, so those serializers encrypt too. Not API. */
final class EncryptingSerializerFactory implements VariableSerializerFactory {

    private final VariableSerializerFactory delegate;
    private final VariableEncryptor encryptor;

    EncryptingSerializerFactory(VariableSerializerFactory delegate, VariableEncryptor encryptor) {
        this.delegate = delegate;
        this.encryptor = encryptor;
    }

    @Override
    public TypedValueSerializer<?> getSerializer(String serializerName) {
        return wrap(delegate.getSerializer(serializerName));
    }

    @Override
    public TypedValueSerializer<?> getSerializer(TypedValue value) {
        return wrap(delegate.getSerializer(value));
    }

    private TypedValueSerializer<?> wrap(TypedValueSerializer<?> serializer) {
        if (serializer == null || serializer instanceof EncryptingSerializer<?>) {
            return serializer;
        }
        return encryptor.wrap(serializer);
    }
}
