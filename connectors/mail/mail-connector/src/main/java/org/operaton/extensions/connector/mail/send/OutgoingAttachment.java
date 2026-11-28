/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail.send;

import java.net.URLConnection;

/** An attachment to send. */
record OutgoingAttachment(String name, String contentType, byte[] content) {

    OutgoingAttachment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("attachment name must not be blank");
        }
        if (contentType == null || contentType.isBlank()) {
            String guessed = URLConnection.guessContentTypeFromName(name);
            contentType = guessed == null ? "application/octet-stream" : guessed;
        }
    }
}
