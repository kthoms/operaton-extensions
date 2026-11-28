/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.consumer;

/** What an inbound record does in the engine. */
public enum InboundAction {
    /** Start a process instance of {@code processDefinitionKey}. */
    START_PROCESS,
    /** Correlate the message {@code messageName} (to a waiting instance or a message start event). */
    CORRELATE_MESSAGE
}
