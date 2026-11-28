/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

/** A record does not fit the subscription's mapping. Deterministic, so the record is poison, not retried. */
public class MappingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
