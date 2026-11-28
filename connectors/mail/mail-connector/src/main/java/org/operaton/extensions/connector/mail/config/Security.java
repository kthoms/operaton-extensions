/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.config;

/** Transport security of an SMTP or IMAP connection. */
public enum Security {
    /** Plain text. Only for local test servers. */
    NONE,
    /** Upgrade a plain connection with STARTTLS (required, the connection fails otherwise). */
    STARTTLS,
    /** TLS from the first byte (SMTPS, IMAPS). */
    SSL
}
