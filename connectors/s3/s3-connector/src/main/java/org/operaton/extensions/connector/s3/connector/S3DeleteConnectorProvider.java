/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.connector;

import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorProvider;
import org.operaton.extensions.connector.s3.internal.S3Action;

/** Registers connector {@code s3-delete} with the Connect SPI. */
public final class S3DeleteConnectorProvider implements ConnectorProvider {

    @Override
    public String getConnectorId() {
        return S3Action.DELETE.id();
    }

    @Override
    public Connector<?> createConnectorInstance() {
        return new S3Connector(S3Action.DELETE);
    }
}
