/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.kafka.connector;

import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorProvider;

/** Registers {@link KafkaProduceConnector} with the Operaton Connect SPI ({@code META-INF/services}). */
public class KafkaProduceConnectorProvider implements ConnectorProvider {

    @Override
    public String getConnectorId() {
        return KafkaProduceConnector.ID;
    }

    @Override
    public Connector<?> createConnectorInstance() {
        return new KafkaProduceConnector();
    }
}
