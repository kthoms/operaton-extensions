/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

/** Validation of key ids and key material shared by the {@code KeyProvider} implementations. Not API. */
public final class Keys {

    /** Length of an AES-256 key in bytes. */
    public static final int KEY_BYTES = 32;

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private Keys() {}

    /**
     * Validates a key id.
     *
     * @param keyId the id to check
     * @return the id
     * @throws VariableEncryptionException if the id is blank, too long or contains other characters than
     *     {@code A-Z a-z 0-9 . _ -}
     */
    public static String requireValidId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new VariableEncryptionException(
                    "Invalid key id '" + keyId + "': use 1 to 64 characters of A-Z a-z 0-9 . _ -");
        }
        return keyId;
    }

    /**
     * Validates that a key is an AES-256 key. Keys that do not expose their encoding (hardware
     * backed) are accepted as long as their algorithm is AES.
     *
     * @param keyId the key's id, for the error message
     * @param key the key to check
     * @return the key
     * @throws VariableEncryptionException if the key is not AES or not 256 bits
     */
    public static SecretKey requireAes256(String keyId, SecretKey key) {
        if (key == null) {
            throw new VariableEncryptionException("The key '" + keyId + "' is null");
        }
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new VariableEncryptionException(
                    "The key '" + keyId + "' is a " + key.getAlgorithm() + " key; AES-256 is required");
        }
        byte[] encoded = key.getEncoded();
        if (encoded != null && encoded.length != KEY_BYTES) {
            throw new VariableEncryptionException("The key '" + keyId + "' has " + encoded.length * 8
                    + " bits; AES-256 (256 bits, " + KEY_BYTES + " bytes) is required");
        }
        return key;
    }
}
