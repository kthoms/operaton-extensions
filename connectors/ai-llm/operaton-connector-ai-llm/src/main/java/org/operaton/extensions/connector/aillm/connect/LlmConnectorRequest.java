/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.connect;

import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorRequest;

/** Request of the LLM connectors: the input parameters, see {@link org.operaton.extensions.connector.aillm.LlmOperation}. */
public class LlmConnectorRequest extends AbstractConnectorRequest<LlmConnectorResponse> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public LlmConnectorRequest(LlmConnector connector) {
        super((Connector<ConnectorRequest<LlmConnectorResponse>>) (Connector) connector);
    }
}
