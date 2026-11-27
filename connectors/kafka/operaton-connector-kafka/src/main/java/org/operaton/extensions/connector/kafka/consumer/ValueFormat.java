/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

/** How the record value is interpreted for mappings. */
public enum ValueFormat {
    /** A JSON document; {@code $.path} sources are available. */
    JSON,
    /** Opaque text; only {@code value} (the whole text) is available. */
    TEXT
}
