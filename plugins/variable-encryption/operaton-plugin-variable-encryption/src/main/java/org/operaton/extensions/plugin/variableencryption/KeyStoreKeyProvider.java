/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import org.operaton.extensions.plugin.variableencryption.internal.Keys;

/**
 * Keys from a JCE key store file (PKCS12 or JCEKS). Every secret key entry is a usable key, its
 * <em>alias is the key id</em> (PKCS12 aliases are case-insensitive: use lower-case ids). The active key
 * must be an AES-256 key; every other key is checked when it is used to decrypt. Create keys with
 * {@code keytool -genseckey -alias 2026-01 -keyalg AES -keysize 256 -storetype PKCS12 -keystore keys.p12}.
 *
 * <p>The key password must equal the store password (the PKCS12 default). The store is read when
 * the provider is created and again on {@link #reload()}, so adding a new key (rotation) does not
 * need a restart when the application calls {@code reload()} after replacing the file.
 * Thread-safe: reads see either the complete old or the complete new set of keys.
 */
public final class KeyStoreKeyProvider implements KeyProvider {

    private final Path path;
    private final String type;
    private final char[] password;
    private final String activeKeyId;

    private volatile Map<String, SecretKey> keys;

    /**
     * Opens the key store and loads its keys.
     *
     * @param path the key store file
     * @param type the key store type, for example {@code PKCS12} or {@code JCEKS}
     * @param password the store password, also used for the key entries
     * @param activeKeyId the alias of the key for new encryptions
     * @throws VariableEncryptionException if the store cannot be read, holds no AES-256 key or lacks the active key
     */
    public KeyStoreKeyProvider(Path path, String type, char[] password, String activeKeyId) {
        this.path = path;
        this.type = type;
        this.password = password == null ? new char[0] : password.clone();
        this.activeKeyId = Keys.requireValidId(activeKeyId);
        this.keys = load();
    }

    /**
     * Reads the key store file again. The active key id must still be present; on failure the
     * previously loaded keys stay in use and the exception is thrown to the caller.
     *
     * @throws VariableEncryptionException if the store cannot be read or is no longer valid
     */
    public void reload() {
        this.keys = load();
    }

    private Map<String, SecretKey> load() {
        KeyStore store;
        try (InputStream in = Files.newInputStream(path)) {
            store = KeyStore.getInstance(type);
            store.load(in, password);
        } catch (IOException | GeneralSecurityException e) {
            throw new VariableEncryptionException(
                    "Cannot read the key store '" + path + "' (" + type + "): " + e.getMessage(), e);
        }
        Map<String, SecretKey> loaded = new LinkedHashMap<>();
        try {
            for (String alias : Collections.list(store.aliases())) {
                if (!store.entryInstanceOf(alias, KeyStore.SecretKeyEntry.class)) {
                    continue;
                }
                Key key = store.getKey(alias, password);
                if (key instanceof SecretKey secretKey) {
                    loaded.put(Keys.requireValidId(alias), secretKey);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new VariableEncryptionException(
                    "Cannot read the keys of the key store '" + path + "': " + e.getMessage(), e);
        }
        SecretKey active = loaded.get(activeKeyId);
        if (active == null) {
            throw new VariableEncryptionException("The key store '" + path + "' has no secret key with the alias '"
                    + activeKeyId + "' (found: " + loaded.keySet() + ")");
        }
        // the other entries are checked when data encrypted with them is read: the store may hold unrelated secrets
        Keys.requireAes256(activeKeyId, active);
        return Map.copyOf(loaded);
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
        return "KeyStoreKeyProvider[" + path + ", active=" + activeKeyId + ", keys=" + keys.keySet() + "]";
    }
}
