/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.variable.serializer.NullValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.operaton.bpm.engine.impl.variable.serializer.VariableSerializerFactory;
import org.operaton.bpm.engine.impl.variable.serializer.VariableSerializers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the encrypting serializers of one engine share: the cipher, the designation policy,
 * the settings and the value memo. Created by the plugin, installed once into the engine's
 * serializer registry. Thread-safe. Not API.
 */
public final class VariableEncryptor {

    private static final Logger LOG = LoggerFactory.getLogger(VariableEncryptor.class);

    private final EnvelopeCipher cipher;
    private final VariableNamePolicy policy;
    private final boolean encryptMarkedValues;
    private final boolean allowPlaintextRead;
    private final ValueMemo memo = new ValueMemo();

    /**
     * Creates the encryptor.
     *
     * @param cipher encrypts and decrypts payloads
     * @param policy designates variables by name
     * @param encryptMarkedValues whether values tagged with {@code EncryptedVariables.encrypted} are encrypted
     * @param allowPlaintextRead whether a designated variable stored in plaintext may be read
     */
    public VariableEncryptor(
            EnvelopeCipher cipher, VariableNamePolicy policy, boolean encryptMarkedValues, boolean allowPlaintextRead) {
        this.cipher = cipher;
        this.policy = policy;
        this.encryptMarkedValues = encryptMarkedValues;
        this.allowPlaintextRead = allowPlaintextRead;
    }

    EnvelopeCipher cipher() {
        return cipher;
    }

    ValueMemo memo() {
        return memo;
    }

    boolean isDesignated(String variableName) {
        return policy.matches(variableName);
    }

    boolean encryptMarkedValues() {
        return encryptMarkedValues;
    }

    boolean allowPlaintextRead() {
        return allowPlaintextRead;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform provides SHA-256", e);
        }
    }

    /**
     * Replaces every serializer of the engine by an encrypting wrapper of the same name and type
     * (the stored serializer name does not change, existing plaintext rows stay readable), and
     * wraps the fallback factory. Call after all plugins have registered their serializers.
     *
     * @param configuration the engine configuration
     */
    public void install(ProcessEngineConfigurationImpl configuration) {
        VariableSerializers serializers = configuration.getVariableSerializers();
        List<TypedValueSerializer<?>> current = serializers.getSerializers();
        int wrapped = 0;
        for (int index = 0; index < current.size(); index++) {
            TypedValueSerializer<?> serializer = current.get(index);
            if (serializer instanceof EncryptingSerializer<?> || serializer instanceof NullValueSerializer) {
                continue;
            }
            serializers.removeSerializer(serializer);
            serializers.addSerializer(wrap(serializer), index);
            wrapped++;
        }
        VariableSerializerFactory fallback = configuration.getFallbackSerializerFactory();
        if (fallback != null && !(fallback instanceof EncryptingSerializerFactory)) {
            configuration.setFallbackSerializerFactory(new EncryptingSerializerFactory(fallback, this));
        }
        LOG.info(
                "Variable encryption active: {} serializers wrapped, active key '{}', designated name patterns {}",
                wrapped,
                cipher.activeKeyId(),
                policy.patterns());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <T extends org.operaton.bpm.engine.variable.value.TypedValue> TypedValueSerializer<T> wrap(
            TypedValueSerializer<?> serializer) {
        return new EncryptingSerializer(serializer, this);
    }
}
