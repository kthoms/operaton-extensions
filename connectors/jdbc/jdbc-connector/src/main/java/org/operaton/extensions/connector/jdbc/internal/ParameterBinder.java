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
package org.operaton.extensions.connector.jdbc.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.SqlTypeHint;

/**
 * Binds one value to a {@link PreparedStatement} with the JDBC setter that matches its type. Values are only ever bound,
 * never rendered into SQL text. Stateless and thread-safe.
 */
public final class ParameterBinder {

    private ParameterBinder() {}

    /**
     * @param name parameter name, for error messages only
     * @param hint explicit type, or {@code null} to derive it from the Java type of {@code value}
     */
    public static void bind(PreparedStatement ps, int index, String name, Object value, SqlTypeHint hint)
            throws SQLException {
        if (hint != null) {
            bindWithHint(ps, index, name, value, hint);
            return;
        }
        if (value == null) {
            ps.setNull(index, Types.NULL);
        } else if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            ps.setString(index, value instanceof Enum<?> e ? e.name() : value.toString());
        } else if (value instanceof Integer v) {
            ps.setInt(index, v);
        } else if (value instanceof Long v) {
            ps.setLong(index, v);
        } else if (value instanceof Short v) {
            ps.setShort(index, v);
        } else if (value instanceof Byte v) {
            ps.setByte(index, v);
        } else if (value instanceof BigDecimal v) {
            ps.setBigDecimal(index, v);
        } else if (value instanceof BigInteger v) {
            ps.setBigDecimal(index, new BigDecimal(v));
        } else if (value instanceof Double v) {
            ps.setDouble(index, v);
        } else if (value instanceof Float v) {
            ps.setFloat(index, v);
        } else if (value instanceof Boolean v) {
            ps.setBoolean(index, v);
        } else if (value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime
                || value instanceof OffsetTime) {
            ps.setObject(index, value);
        } else if (value instanceof ZonedDateTime v) {
            ps.setObject(index, v.toOffsetDateTime());
        } else if (value instanceof Instant v) {
            ps.setObject(index, v.atOffset(ZoneOffset.UTC));
        } else if (value instanceof Timestamp v) {
            ps.setTimestamp(index, v);
        } else if (value instanceof java.sql.Date v) {
            ps.setDate(index, v);
        } else if (value instanceof Time v) {
            ps.setTime(index, v);
        } else if (value instanceof java.util.Date v) {
            ps.setTimestamp(index, new Timestamp(v.getTime()));
        } else if (value instanceof UUID v) {
            ps.setObject(index, v);
        } else if (value instanceof byte[] v) {
            ps.setBytes(index, v);
        } else {
            throw new JdbcConnectorException("Parameter ':" + name + "' has unsupported type "
                    + value.getClass().getName()
                    + "; supported are strings, numbers, booleans, java.time and java.util dates, UUID, byte[] and"
                    + " lists of these. Convert the value, or declare its type in parameterTypes.");
        }
    }

    private static void bindWithHint(PreparedStatement ps, int index, String name, Object value, SqlTypeHint hint)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, hint.sqlType());
            return;
        }
        try {
            switch (hint) {
                case STRING -> ps.setString(index, value.toString());
                case INTEGER -> ps.setInt(index, toBigDecimal(value).intValueExact());
                case BIGINT -> ps.setLong(index, toBigDecimal(value).longValueExact());
                case DECIMAL -> ps.setBigDecimal(index, toBigDecimal(value));
                case DOUBLE ->
                    ps.setDouble(
                            index,
                            value instanceof Number n
                                    ? n.doubleValue()
                                    : Double.parseDouble(value.toString().trim()));
                case BOOLEAN -> ps.setBoolean(index, toBoolean(value));
                case DATE ->
                    ps.setObject(
                            index,
                            value instanceof LocalDate d
                                    ? d
                                    : value instanceof java.util.Date d
                                            ? new java.sql.Date(d.getTime()).toLocalDate()
                                            : LocalDate.parse(value.toString().trim()));
                case TIME ->
                    ps.setObject(
                            index,
                            value instanceof LocalTime t
                                    ? t
                                    : LocalTime.parse(value.toString().trim()));
                case TIMESTAMP -> ps.setObject(index, toLocalDateTime(value));
                case TIMESTAMP_TZ -> ps.setObject(index, toOffsetDateTime(value));
                case UUID ->
                    ps.setObject(
                            index,
                            value instanceof UUID u
                                    ? u
                                    : UUID.fromString(value.toString().trim()));
                case JSON -> ps.setObject(index, value.toString(), Types.OTHER);
                case BINARY ->
                    ps.setBytes(
                            index,
                            value instanceof byte[] b
                                    ? b
                                    : Base64.getDecoder()
                                            .decode(value.toString().trim()));
            }
        } catch (ArithmeticException | DateTimeParseException | IllegalArgumentException e) {
            throw new JdbcConnectorException(
                    "Parameter ':" + name + "' of type " + value.getClass().getName() + " cannot be converted to "
                            + hint + ": " + e.getMessage(),
                    e);
        }
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (value instanceof Double || value instanceof Float) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        return new BigDecimal(value.toString().trim());
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new IllegalArgumentException("not a boolean: '" + text + "'");
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime t) {
            return t;
        }
        if (value instanceof Timestamp t) {
            return t.toLocalDateTime();
        }
        if (value instanceof java.util.Date d) {
            return new Timestamp(d.getTime()).toLocalDateTime();
        }
        return LocalDateTime.parse(value.toString().trim());
    }

    private static OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime t) {
            return t;
        }
        if (value instanceof ZonedDateTime t) {
            return t.toOffsetDateTime();
        }
        if (value instanceof Instant t) {
            return t.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.util.Date d) {
            return d.toInstant().atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(value.toString().trim());
    }
}
