/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

/** The requested object (or version) does not exist. */
public class S3ObjectNotFoundException extends S3ConnectorException {

    private static final long serialVersionUID = 1L;

    public S3ObjectNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
