/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.plugin.variableencryption.internal.EnvelopeCipher;
import org.operaton.extensions.plugin.variableencryption.internal.VariableEncryptor;
import org.operaton.extensions.plugin.variableencryption.internal.VariableNamePolicy;

/**
 * Process engine plugin that encrypts selected process variables at rest (AES-256-GCM) and
 * decrypts them transparently when they are read through the engine API.
 *
 * <p>It wraps the engine's variable serializers, so every place the engine persists a variable
 * value is covered: the runtime table, the historic variable instances and the historic variable
 * updates (the history is a copy of the runtime columns, so it holds the same ciphertext). Which
 * variables are encrypted is decided by <em>name</em> ({@link #setVariableNames(Collection)}) and
 * by <em>tag</em> ({@link EncryptedVariables#encrypted(org.operaton.bpm.engine.variable.value.TypedValue)}).
 * See the README for exactly what is and is not covered.
 *
 * <p>Register it as a {@code processEnginePlugin}; configure before the engine is built. Exactly one
 * key source is required: {@link #setKeyProvider(KeyProvider)}, {@link #setKeys(Map) keys} with
 * {@link #setActiveKeyId(String) activeKeyId}, or {@link #setKeyStore(String) keyStore} with
 * {@link #setKeyStorePassword(String) keyStorePassword} and {@code activeKeyId}. Invalid
 * configuration fails engine startup with a {@link VariableEncryptionException}.
 *
 * <p>The plugin installs itself after the engine is built, so it also wraps serializers registered
 * by other plugins (Spin). One plugin instance serves one engine at a time. Thread-safety:
 * configure before the engine is built; afterwards the instance is safe for concurrent use.
 */
public class VariableEncryptionPlugin extends AbstractProcessEnginePlugin {

    private KeyProvider keyProvider;
    private Map<String, String> keys = Map.of();
    private String activeKeyId;
    private String keyStore;
    private String keyStoreType = "PKCS12";
    private String keyStorePassword;
    private List<String> variableNames = List.of();
    private boolean encryptMarkedValues = true;
    private boolean allowPlaintextRead = false;

    private volatile VariableEncryptor encryptor;

    /**
     * Uses this key provider (for example one backed by a KMS). Takes precedence over every other key setting,
     * which must then be left unset.
     */
    public void setKeyProvider(KeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * Static keys: key id to Base64 encoded 32 byte key, or {@code env:NAME} to read the key from the
     * environment variable {@code NAME}. Requires {@link #setActiveKeyId(String)}.
     */
    public void setKeys(Map<String, String> keys) {
        this.keys = keys == null ? Map.of() : new LinkedHashMap<>(keys);
    }

    /** The id of the key that encrypts new values (static keys and key store); all other keys only decrypt. */
    public void setActiveKeyId(String activeKeyId) {
        this.activeKeyId = activeKeyId;
    }

    /** Path of a JCE key store file whose secret key entries (alias = key id) are the keys. */
    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    /** Key store type, default {@code PKCS12}. */
    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    /** Key store (and key) password, or {@code env:NAME} to read it from the environment variable {@code NAME}. */
    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    /**
     * Names of the variables to encrypt: globs ({@code iban}, {@code applicant*}, {@code ?secret}) or, with the
     * prefix {@code regex:}, regular expressions; each must match the whole name. Default: none (only
     * tagged values are encrypted).
     */
    public void setVariableNames(Collection<String> variableNames) {
        this.variableNames = variableNames == null ? List.of() : new ArrayList<>(variableNames);
    }

    /** Whether values tagged with {@link EncryptedVariables} are encrypted; default {@code true}. */
    public void setEncryptMarkedValues(boolean encryptMarkedValues) {
        this.encryptMarkedValues = encryptMarkedValues;
    }

    /**
     * Whether a variable that is designated by name but stored in plaintext may be read; default
     * {@code false} (the read fails, which also catches a tampered row replaced by plaintext). Set it
     * to {@code true} while existing plaintext data is migrated.
     */
    public void setAllowPlaintextRead(boolean allowPlaintextRead) {
        this.allowPlaintextRead = allowPlaintextRead;
    }

    /**
     * Validates the configuration and opens the key source; called by {@code preInit} and usable for
     * fail-fast startup checks (the Spring Boot starter calls it when the bean is created).
     *
     * @return the key provider that is going to be used
     * @throws VariableEncryptionException if the configuration is invalid or the keys cannot be loaded
     */
    public KeyProvider validate() {
        return build().keyProvider;
    }

    private Built build() {
        KeyProvider provider = resolveKeyProvider();
        VariableNamePolicy policy = new VariableNamePolicy(variableNames);
        if (policy.patterns().isEmpty() && !encryptMarkedValues) {
            throw new VariableEncryptionException(
                    "Nothing would be encrypted: no variable names are configured and encryptMarkedValues is off");
        }
        return new Built(
                provider,
                new VariableEncryptor(new EnvelopeCipher(provider), policy, encryptMarkedValues, allowPlaintextRead));
    }

    private KeyProvider resolveKeyProvider() {
        boolean hasStatic = !keys.isEmpty();
        boolean hasKeyStore = keyStore != null && !keyStore.isBlank();
        int sources = (keyProvider != null ? 1 : 0) + (hasStatic ? 1 : 0) + (hasKeyStore ? 1 : 0);
        if (sources == 0) {
            throw new VariableEncryptionException("No key source configured: set keyProvider, keys with activeKeyId,"
                    + " or keyStore with keyStorePassword and activeKeyId");
        }
        if (sources > 1) {
            throw new VariableEncryptionException(
                    "More than one key source configured (keyProvider, keys, keyStore): use exactly one");
        }
        if (keyProvider != null) {
            return keyProvider;
        }
        if (hasStatic) {
            return new StaticKeyProvider(activeKeyId, keys);
        }
        if (keyStorePassword == null) {
            throw new VariableEncryptionException("keyStorePassword is required with keyStore");
        }
        return new KeyStoreKeyProvider(
                Path.of(keyStore),
                keyStoreType,
                resolvePassword(keyStorePassword).toCharArray(),
                activeKeyId);
    }

    private static String resolvePassword(String configured) {
        if (configured.startsWith("env:")) {
            String variable = configured.substring("env:".length()).strip();
            String value = System.getenv(variable);
            if (value == null) {
                throw new VariableEncryptionException(
                        "keyStorePassword refers to the environment variable '" + variable + "', which is not set");
            }
            return value;
        }
        return configured;
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl processEngineConfiguration) {
        Built built = build();
        this.encryptor = built.encryptor;
    }

    @Override
    public void postProcessEngineBuild(ProcessEngine processEngine) {
        VariableEncryptor current = encryptor;
        if (current == null) {
            throw new VariableEncryptionException("The plugin was not initialized: preInit did not run");
        }
        current.install((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration());
    }

    private record Built(KeyProvider keyProvider, VariableEncryptor encryptor) {}
}
