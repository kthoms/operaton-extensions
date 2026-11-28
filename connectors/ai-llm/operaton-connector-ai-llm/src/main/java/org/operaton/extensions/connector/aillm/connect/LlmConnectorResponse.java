/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.connect;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorResponse;

/** Response of the LLM connectors: the output parameters, see {@link org.operaton.extensions.connector.aillm.LlmOperation}. */
public class LlmConnectorResponse extends AbstractConnectorResponse {

    private final Map<String, Object> outputs;

    public LlmConnectorResponse(Map<String, Object> outputs) {
        this.outputs = new LinkedHashMap<>(outputs);
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.putAll(outputs);
    }
}
