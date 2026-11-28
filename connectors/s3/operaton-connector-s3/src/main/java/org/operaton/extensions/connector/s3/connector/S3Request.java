/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.connector;

import org.operaton.connect.impl.AbstractConnectorRequest;
import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorRequest;

/** The request of an S3 connector: the parameters are documented in the README. */
public final class S3Request extends AbstractConnectorRequest<S3Response> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    S3Request(S3Connector connector) {
        super((Connector<ConnectorRequest<S3Response>>) (Connector) connector);
    }
}
