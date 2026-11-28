/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.connect.Connectors;
import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorRequest;
import org.operaton.extensions.connector.s3.S3ConnectorException;

class S3ConnectorRegistrationTest {

    @Test
    void allConnectorsAreRegisteredThroughTheConnectSpi() {
        for (String id : List.of("s3-put", "s3-get", "s3-list", "s3-copy", "s3-delete", "s3-presign")) {
            Connector<? extends ConnectorRequest<?>> connector = Connectors.getConnector(id);
            assertThat(connector).as(id).isNotNull();
            assertThat(connector.getId()).isEqualTo(id);
        }
    }

    @Test
    void failsLoudlyOutsideAnEngineCommand() {
        Connector<S3Request> connector = Connectors.getConnector("s3-list");
        S3Request request = connector.createRequest();
        org.assertj.core.api.Assertions.assertThatThrownBy(request::execute)
                .isInstanceOf(S3ConnectorException.class)
                .hasMessageContaining("process engine command");
    }
}
