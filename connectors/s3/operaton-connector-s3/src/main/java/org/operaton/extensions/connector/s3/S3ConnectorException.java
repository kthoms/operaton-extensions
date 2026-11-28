/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import org.operaton.connect.ConnectorException;

/**
 * Thrown for every failure of an S3 operation or of its configuration. The message names the
 * operation, bucket and key involved; credentials never appear in it.
 */
public class S3ConnectorException extends ConnectorException {

    private static final long serialVersionUID = 1L;

    public S3ConnectorException(String message) {
        super(message);
    }

    public S3ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
