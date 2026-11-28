/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

/** Thrown by a {@link KeyProvider} for a key id it does not know (for example a retired key that was removed). */
public class UnknownKeyException extends VariableEncryptionException {

    private static final long serialVersionUID = 1L;

    private final String keyId;

    /** Creates the exception for the given key id. */
    public UnknownKeyException(String keyId) {
        super("No encryption key with id '" + keyId + "' is available; data encrypted with it cannot be read");
        this.keyId = keyId;
    }

    /**
     * Creates the exception for the given key id, naming the variable that could not be read.
     *
     * @param keyId the key id stored with the encrypted value
     * @param variableName the variable (or other named value) being decrypted
     */
    public UnknownKeyException(String keyId, String variableName) {
        super("Cannot decrypt variable '" + variableName + "': no encryption key with id '" + keyId
                + "' is available (was the key removed or is the key provider configured with other keys?)");
        this.keyId = keyId;
    }

    /** The key id that could not be resolved. */
    public String getKeyId() {
        return keyId;
    }
}
