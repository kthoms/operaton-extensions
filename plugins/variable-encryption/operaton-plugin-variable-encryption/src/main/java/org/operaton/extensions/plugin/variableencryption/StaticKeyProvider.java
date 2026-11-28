/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.operaton.extensions.plugin.variableencryption.internal.Keys;

/**
 * Keys given in configuration, optionally read from environment variables. Immutable and
 * thread-safe.
 *
 * <p>Each key is 32 random bytes, Base64 encoded (for example {@code openssl rand -base64 32}). A
 * configured value of the form {@code env:NAME} is replaced by the value of the environment
 * variable {@code NAME} when the provider is created; a missing or empty variable fails with
 * {@link VariableEncryptionException}. Rotation: add the new key, make it the active one, keep
 * the old ones for as long as data encrypted with them exists.
 */
public final class StaticKeyProvider implements KeyProvider {

    private static final String ENV_PREFIX = "env:";

    private final String activeKeyId;
    private final Map<String, SecretKey> keys;

    /**
     * Creates the provider from Base64 encoded keys (or {@code env:NAME} references).
     *
     * @param activeKeyId the key used for new encryptions; must be one of the keys
     * @param encodedKeys key id to Base64 key material or {@code env:NAME}
     * @throws VariableEncryptionException on an invalid id, key material of the wrong size or an unset variable
     */
    public StaticKeyProvider(String activeKeyId, Map<String, String> encodedKeys) {
        this(activeKeyId, encodedKeys, System::getenv);
    }

    StaticKeyProvider(String activeKeyId, Map<String, String> encodedKeys, Function<String, String> environment) {
        if (encodedKeys == null || encodedKeys.isEmpty()) {
            throw new VariableEncryptionException("No encryption keys configured");
        }
        Map<String, SecretKey> resolved = new LinkedHashMap<>();
        encodedKeys.forEach((id, material) -> {
            Keys.requireValidId(id);
            resolved.put(id, decode(id, material, environment));
        });
        this.activeKeyId = requireKnownActive(activeKeyId, resolved);
        this.keys = Map.copyOf(resolved);
    }

    /**
     * Creates the provider from raw key bytes.
     *
     * @param activeKeyId the key used for new encryptions; must be one of the keys
     * @param rawKeys key id to 32 bytes of key material (copied)
     * @return the provider
     */
    public static StaticKeyProvider ofRawKeys(String activeKeyId, Map<String, byte[]> rawKeys) {
        Map<String, String> encoded = new LinkedHashMap<>();
        rawKeys.forEach((id, bytes) -> encoded.put(id, Base64.getEncoder().encodeToString(bytes)));
        return new StaticKeyProvider(activeKeyId, encoded);
    }

    private static String requireKnownActive(String activeKeyId, Map<String, SecretKey> keys) {
        Keys.requireValidId(activeKeyId);
        if (!keys.containsKey(activeKeyId)) {
            throw new VariableEncryptionException(
                    "The active key id '" + activeKeyId + "' is not among the configured keys " + keys.keySet());
        }
        return activeKeyId;
    }

    private static SecretKey decode(String id, String material, Function<String, String> environment) {
        if (material == null || material.isBlank()) {
            throw new VariableEncryptionException("The key '" + id + "' has no key material");
        }
        String encoded = material.strip();
        if (encoded.startsWith(ENV_PREFIX)) {
            String variable = encoded.substring(ENV_PREFIX.length()).strip();
            encoded = environment.apply(variable);
            if (encoded == null || encoded.isBlank()) {
                throw new VariableEncryptionException(
                        "The key '" + id + "' refers to the environment variable '" + variable + "', which is not set");
            }
            encoded = encoded.strip();
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // never include the value: it is key material
            throw new VariableEncryptionException("The key '" + id + "' is not valid Base64", e);
        }
        if (raw.length != Keys.KEY_BYTES) {
            throw new VariableEncryptionException("The key '" + id + "' has " + raw.length * 8 + " bits; AES-256 needs "
                    + Keys.KEY_BYTES + " bytes (256 bits), Base64 encoded");
        }
        return new SecretKeySpec(raw, "AES");
    }

    @Override
    public String activeKeyId() {
        return activeKeyId;
    }

    @Override
    public SecretKey key(String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new UnknownKeyException(keyId);
        }
        return key;
    }

    @Override
    public String toString() {
        return "StaticKeyProvider[active=" + activeKeyId + ", keys=" + keys.keySet() + "]";
    }
}
