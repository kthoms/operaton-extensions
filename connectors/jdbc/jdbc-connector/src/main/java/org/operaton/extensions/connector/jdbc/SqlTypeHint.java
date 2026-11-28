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

import java.sql.Types;
import java.util.Locale;

/**
 * Explicit JDBC type of a bound parameter ({@code parameterTypes}). Without a hint the type is derived from the Java
 * type of the value. A hint is needed for {@code null} values on strictly typed columns and when a value is supplied as
 * text (for example a UUID or a date read from a form).
 */
public enum SqlTypeHint {
    STRING(Types.VARCHAR),
    INTEGER(Types.INTEGER),
    BIGINT(Types.BIGINT),
    DECIMAL(Types.NUMERIC),
    DOUBLE(Types.DOUBLE),
    BOOLEAN(Types.BOOLEAN),
    DATE(Types.DATE),
    TIME(Types.TIME),
    TIMESTAMP(Types.TIMESTAMP),
    TIMESTAMP_TZ(Types.TIMESTAMP_WITH_TIMEZONE),
    UUID(Types.OTHER),
    JSON(Types.OTHER),
    BINARY(Types.VARBINARY);

    private final int sqlType;

    SqlTypeHint(int sqlType) {
        this.sqlType = sqlType;
    }

    /** The {@link java.sql.Types} constant used for {@code setNull}. */
    public int sqlType() {
        return sqlType;
    }

    /** Parses a hint, case-insensitive; fails for unknown names. */
    public static SqlTypeHint parse(String parameterName, Object value) {
        if (value instanceof SqlTypeHint hint) {
            return hint;
        }
        String text = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new JdbcConnectorException("Unknown type '" + value + "' for parameter '" + parameterName
                    + "' in parameterTypes; supported: " + java.util.Arrays.toString(values()));
        }
    }
}
