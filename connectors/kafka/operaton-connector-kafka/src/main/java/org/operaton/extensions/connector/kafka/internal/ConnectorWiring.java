/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.internal;

import org.operaton.connect.Connectors;
import org.operaton.connect.spi.Connector;
import org.operaton.extensions.connector.kafka.connector.KafkaProduceConnector;
import org.operaton.extensions.connector.kafka.producer.KafkaRecordSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hands the configured sender to the {@code kafka-produce} connector. Loaded only when Operaton Connect is present. */
public final class ConnectorWiring {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorWiring.class);

    private ConnectorWiring() {}

    /** Whether the Connect SPI is on the classpath. */
    public static boolean available() {
        try {
            Class.forName("org.operaton.connect.Connectors", false, ConnectorWiring.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Sets the sender on the shared connector instance.
     *
     * @throws IllegalStateException when the connector is not registered (the provider file is not visible to the
     *     class loader that loaded the Connect SPI)
     */
    public static void wire(KafkaRecordSender sender) {
        Connector<?> connector = Connectors.getConnector(KafkaProduceConnector.ID);
        if (!(connector instanceof KafkaProduceConnector kafka)) {
            throw new IllegalStateException(("The connector '%s' is not registered with Operaton Connect (found: %s);"
                            + " is the extension jar on the same class path as operaton-connect-core?")
                    .formatted(KafkaProduceConnector.ID, connector));
        }
        kafka.setSender(sender);
        LOG.debug("Connector '{}' uses the configured Kafka producer", KafkaProduceConnector.ID);
    }
}
