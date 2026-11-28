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

/**
 * Thrown for every failure of the JDBC connector: invalid requests, unknown data sources, SQL errors and result
 * handling errors. It reaches the engine, so the job fails visibly and creates an incident. The message never contains
 * bound parameter values.
 */
public class JdbcConnectorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sqlState;
    private final int vendorCode;

    public JdbcConnectorException(String message) {
        super(message);
        this.sqlState = null;
        this.vendorCode = 0;
    }

    public JdbcConnectorException(String message, Throwable cause) {
        super(message, cause);
        if (cause instanceof java.sql.SQLException sql) {
            this.sqlState = sql.getSQLState();
            this.vendorCode = sql.getErrorCode();
        } else {
            this.sqlState = null;
            this.vendorCode = 0;
        }
    }

    /** The SQLState of the underlying {@link java.sql.SQLException}, or {@code null} if the failure was not an SQL error. */
    public String getSqlState() {
        return sqlState;
    }

    /** The vendor error code of the underlying {@link java.sql.SQLException}, {@code 0} if none. */
    public int getVendorCode() {
        return vendorCode;
    }
}
