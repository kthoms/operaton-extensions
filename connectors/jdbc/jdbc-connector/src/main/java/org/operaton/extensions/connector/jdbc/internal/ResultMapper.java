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
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.operaton.extensions.connector.jdbc.ColumnCase;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;

/**
 * Maps the current row of a {@link ResultSet} to a {@code Map<String, Object>} with serializable, exact Java values:
 *
 * <ul>
 *   <li>{@code DECIMAL}/{@code NUMERIC} to {@link BigDecimal} (never double), {@code BIGINT} to {@link Long}, smaller
 *       integers to {@link Integer}, {@code FLOAT}/{@code DOUBLE} to {@link Double}, {@code REAL} to {@link Float}
 *   <li>{@code DATE} to {@link LocalDate}, {@code TIME} to {@link LocalTime}, {@code TIMESTAMP} to
 *       {@link LocalDateTime}, {@code TIMESTAMP WITH TIME ZONE} to {@link OffsetDateTime}
 *   <li>character types and {@code CLOB} to {@link String}, binary types and {@code BLOB} to {@code byte[]}
 *   <li>{@code UUID} to {@link UUID}, SQL arrays to {@link List}, anything else (for example {@code json}) to its text
 * </ul>
 *
 * Column labels must be unique after the {@link ColumnCase} is applied. Stateless and thread-safe.
 */
public final class ResultMapper {

    private ResultMapper() {}

    /** Reads the column labels of the result set, applying the case rule; duplicates fail. */
    public static List<String> labels(ResultSetMetaData md, ColumnCase columnCase) throws SQLException {
        int count = md.getColumnCount();
        List<String> labels = new ArrayList<>(count);
        for (int c = 1; c <= count; c++) {
            String label = columnCase.apply(md.getColumnLabel(c));
            if (labels.contains(label)) {
                throw new JdbcConnectorException("Duplicate column label '" + label
                        + "' in the result; give the columns distinct aliases (AS name)");
            }
            labels.add(label);
        }
        return labels;
    }

    public static Map<String, Object> row(ResultSet rs, ResultSetMetaData md, List<String> labels) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int c = 1; c <= labels.size(); c++) {
            row.put(labels.get(c - 1), value(rs, md, c));
        }
        return row;
    }

    private static Object value(ResultSet rs, ResultSetMetaData md, int column) throws SQLException {
        int type = md.getColumnType(column);
        switch (type) {
            case Types.DATE:
                return rs.getObject(column, LocalDate.class);
            case Types.TIME:
                return rs.getObject(column, LocalTime.class);
            case Types.TIME_WITH_TIMEZONE:
                return rs.getObject(column, OffsetTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return rs.getObject(column, OffsetDateTime.class);
            case Types.TIMESTAMP:
                // pgjdbc reports timestamptz as Types.TIMESTAMP
                String typeName = md.getColumnTypeName(column);
                if (typeName != null
                                && typeName.toLowerCase(Locale.ROOT)
                                        .replace(" ", "")
                                        .contains("timestamptz")
                        || typeName != null && typeName.toUpperCase(Locale.ROOT).contains("WITH TIME ZONE")) {
                    return rs.getObject(column, OffsetDateTime.class);
                }
                return rs.getObject(column, LocalDateTime.class);
            case Types.CLOB:
            case Types.NCLOB:
            case Types.SQLXML:
                return rs.getString(column);
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return rs.getBytes(column);
            default:
                return normalise(rs.getObject(column));
        }
    }

    static Object normalise(Object value) throws SQLException {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigDecimal
                || value instanceof Double
                || value instanceof Float
                || value instanceof UUID
                || value instanceof byte[]
                || value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime
                || value instanceof OffsetTime) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short) {
            return ((Number) value).intValue();
        }
        if (value instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (value instanceof Timestamp t) {
            return t.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Time t) {
            return t.toLocalTime();
        }
        if (value instanceof Array array) {
            try {
                Object elements = array.getArray();
                List<Object> list = new ArrayList<>();
                if (elements instanceof Object[] objects) {
                    for (Object element : objects) {
                        list.add(normalise(element));
                    }
                } else {
                    int length = java.lang.reflect.Array.getLength(elements);
                    for (int i = 0; i < length; i++) {
                        list.add(normalise(java.lang.reflect.Array.get(elements, i)));
                    }
                }
                return list;
            } finally {
                array.free();
            }
        }
        if (value instanceof Object[] objects) {
            List<Object> list = new ArrayList<>();
            for (Object element : objects) {
                list.add(normalise(element));
            }
            return list;
        }
        return value.toString();
    }
}
