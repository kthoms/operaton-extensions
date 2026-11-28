/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.producer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides what happens when a record that is sent after the engine transaction committed cannot be delivered. The
 * transaction cannot be undone at that point, so the process has already moved on.
 */
@FunctionalInterface
public interface DeliveryFailureHandler {

    /**
     * Called on the thread that committed the transaction.
     *
     * @param message the record that was not delivered
     * @param failure why
     * @throws RuntimeException to propagate the failure to the caller of the engine command (the commit stays)
     */
    void onFailure(OutboundMessage message, KafkaDeliveryException failure);

    /** Logs the failure at ERROR (topic, partition, key length, headers' names; never the payload) and goes on. */
    static DeliveryFailureHandler logging() {
        return new Logging();
    }

    /** Rethrows the failure to the caller of the engine command, after logging it at ERROR. */
    static DeliveryFailureHandler throwing() {
        return (message, failure) -> {
            new Logging().onFailure(message, failure);
            throw failure;
        };
    }

    /** Implementation of {@link #logging()}. */
    final class Logging implements DeliveryFailureHandler {
        private static final Logger LOG = LoggerFactory.getLogger(DeliveryFailureHandler.class);

        @Override
        public void onFailure(OutboundMessage message, KafkaDeliveryException failure) {
            LOG.error(
                    "Record for topic '{}' (partition {}, key {} bytes, {} headers) was NOT delivered after the engine"
                            + " transaction committed",
                    message.topic(),
                    message.partition(),
                    message.key() == null ? 0 : message.key().length,
                    message.headers().size(),
                    failure);
        }
    }
}
