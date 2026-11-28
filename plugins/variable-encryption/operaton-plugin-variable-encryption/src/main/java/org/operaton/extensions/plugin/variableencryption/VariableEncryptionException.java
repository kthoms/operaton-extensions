/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import org.operaton.bpm.engine.ProcessEngineException;

/**
 * Thrown when a variable cannot be encrypted or decrypted: wrong or unknown key, modified
 * (tampered) data, an unreadable envelope, a plaintext value where an encrypted one is required,
 * or a configuration that cannot be used.
 *
 * <p>The message names the variable and the key id, never the value or the key material. There
 * is no fallback to plaintext: the exception reaches the caller of the engine API (and fails the
 * surrounding command).
 */
public class VariableEncryptionException extends ProcessEngineException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a message. */
    public VariableEncryptionException(String message) {
        super(message);
    }

    /** Creates the exception with a message and a cause. */
    public VariableEncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
