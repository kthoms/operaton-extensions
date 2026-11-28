/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc.connect;

import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorProvider;

/** Registers the {@code jdbc-update} connector with the Operaton Connect {@code ServiceLoader}. */
public class JdbcUpdateConnectorProvider implements ConnectorProvider {

    @Override
    public String getConnectorId() {
        return JdbcUpdateConnector.ID;
    }

    @Override
    public Connector<?> createConnectorInstance() {
        return new JdbcUpdateConnector();
    }
}
