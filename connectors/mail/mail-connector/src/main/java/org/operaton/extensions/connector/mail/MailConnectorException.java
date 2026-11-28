/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

/** Thrown when a mail operation fails or is configured incorrectly. Never swallowed: it reaches the engine. */
public class MailConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MailConnectorException(String message) {
        super(message);
    }

    public MailConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
