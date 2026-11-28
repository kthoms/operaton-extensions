/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.connect;

import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorProvider;
import org.operaton.extensions.connector.aillm.LlmOperation;

/** Registers the {@code llm-prompt} connector with Connect (ServiceLoader). */
public class LlmPromptConnectorProvider implements ConnectorProvider {

    @Override
    public String getConnectorId() {
        return LlmOperation.PROMPT.connectorId();
    }

    @Override
    public Connector<?> createConnectorInstance() {
        return new LlmConnector(LlmOperation.PROMPT);
    }
}
