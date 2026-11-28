/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

/**
 * The object is larger than the allowed download size. Downloads end up in the engine database, so
 * the limit is enforced instead of truncating or silently storing a huge variable.
 */
public class S3ObjectTooLargeException extends S3ConnectorException {

    private static final long serialVersionUID = 1L;

    public S3ObjectTooLargeException(String message) {
        super(message);
    }
}
