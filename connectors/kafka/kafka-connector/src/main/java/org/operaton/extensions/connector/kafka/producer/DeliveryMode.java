/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

/** When a record is sent relative to the engine transaction. */
public enum DeliveryMode {
    /** After the engine transaction committed; nothing is sent on rollback. The default. */
    AFTER_COMMIT,
    /** Immediately and synchronously, inside the transaction; the offset is known, but a rollback does not recall it. */
    IMMEDIATE
}
