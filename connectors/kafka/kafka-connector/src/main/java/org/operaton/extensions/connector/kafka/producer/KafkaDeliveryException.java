/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

/**
 * A record could not be delivered to Kafka (error, interruption or timeout waiting for the acknowledgement). After a
 * timeout the record may still have been written: delivery is at-least-once.
 */
public class KafkaDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public KafkaDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
