/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.mail;

import java.util.Collections;
import java.util.Map;
import org.operaton.connect.spi.ConnectorResponse;

/** Response of a connector without output. */
public final class EmptyResponse implements ConnectorResponse {

    @Override
    public Map<String, Object> getResponseParameters() {
        return Collections.emptyMap();
    }

    @Override
    public <V> V getResponseParameter(String name) {
        return null;
    }
}
