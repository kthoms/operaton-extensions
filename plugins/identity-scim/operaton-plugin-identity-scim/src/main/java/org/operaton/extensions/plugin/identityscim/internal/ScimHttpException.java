/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import org.operaton.bpm.engine.impl.identity.IdentityProviderException;

/** A SCIM or token endpoint answered with an unexpected HTTP status. */
public final class ScimHttpException extends IdentityProviderException {

    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates the exception.
     *
     * @param status the HTTP status
     * @param message what was attempted and what the server answered
     */
    public ScimHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * Returns the HTTP status of the response.
     *
     * @return the status code
     */
    public int getStatus() {
        return status;
    }
}
