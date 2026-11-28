/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import javax.crypto.SecretKey;

/**
 * Supplies the AES-256 keys the plugin encrypts and decrypts with. This is the integration point
 * for key management systems: implement it on top of a KMS or vault client (fetching and caching
 * the data keys) and register the instance with
 * {@link VariableEncryptionPlugin#setKeyProvider(KeyProvider)}. The plugin ships a
 * {@link StaticKeyProvider} (configuration or environment) and a {@link KeyStoreKeyProvider}
 * (JCE key store file).
 *
 * <p>Key rotation: {@link #activeKeyId()} names the key for <em>new</em> encryptions; every value
 * carries the id of the key that encrypted it, and {@link #key(String)} must keep returning all
 * keys that still protect stored data.
 *
 * <p>Implementations must be thread-safe: they are called concurrently from the engine's threads
 * and the job executor, on every encryption and decryption. Cache remote keys; never block for
 * long. Keys must be AES keys of 256 bits; implementations must not log key material.
 */
public interface KeyProvider {

    /**
     * The id of the key used to encrypt new values: 1 to 64 characters of {@code A-Z a-z 0-9 . _ -}.
     *
     * @return the active key id, never {@code null}
     */
    String activeKeyId();

    /**
     * Returns the key with the given id.
     *
     * @param keyId the id stored with the encrypted value
     * @return the key, never {@code null}
     * @throws UnknownKeyException if the provider has no key with this id
     */
    SecretKey key(String keyId);
}
