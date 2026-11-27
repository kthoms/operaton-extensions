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
package org.operaton.extensions.connector.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A connection for one statement. Closing it returns a pooled connection to its pool; the engine's own connection (when
 * participating in the engine transaction) is never closed or committed here.
 */
public final class ConnectionHandle implements AutoCloseable {

    private final Connection connection;
    private final boolean engineOwned;
    private final JdbcDataSourceProperties settings;

    ConnectionHandle(Connection connection, boolean engineOwned, JdbcDataSourceProperties settings) {
        this.connection = connection;
        this.engineOwned = engineOwned;
        this.settings = settings;
    }

    public Connection connection() {
        return connection;
    }

    /** {@code true} when the statement runs on the engine's connection inside the engine's transaction. */
    public boolean participatesInEngineTransaction() {
        return engineOwned;
    }

    public JdbcDataSourceProperties settings() {
        return settings;
    }

    @Override
    public void close() throws SQLException {
        if (!engineOwned) {
            connection.close();
        }
    }
}
