/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.connector;

import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorResponse;

/** The response of an S3 connector: the response parameters are documented in the README. */
public final class S3Response extends AbstractConnectorResponse {

    private final Map<String, Object> result;

    S3Response(Map<String, Object> result) {
        this.result = result;
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.putAll(result);
    }
}
