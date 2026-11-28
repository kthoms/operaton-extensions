/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

/** What to do with a record that can never be processed (undecodable, mapping fails, retries exhausted). */
public enum PoisonHandling {
    /** Publish the original record with {@code x-dlt-*} headers to the dead-letter topic, then go on. */
    DEAD_LETTER,
    /** Log at ERROR (topic, partition, offset, reason; never the payload) and go on. The record is lost. */
    SKIP
}
