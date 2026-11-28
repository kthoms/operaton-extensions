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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Names of the input parameters of the connectors, and the conversion of loosely typed input values (as produced by
 * expressions and scripts) to the types {@link JdbcOperations} needs. Stateless.
 */
public final class JdbcParameters {

    public static final String SQL = "sql";
    public static final String DATASOURCE = "datasource";
    public static final String PARAMETERS = "parameters";
    public static final String PARAMETER_TYPES = "parameterTypes";
    public static final String TIMEOUT = "timeout";
    public static final String MAX_ROWS = "maxRows";
    public static final String TRUNCATE = "truncate";
    public static final String COLUMN_CASE = "columnCase";
    public static final String RESULT_FORMAT = "resultFormat";
    public static final String RESULT_AS = "resultAs";
    public static final String DECIMALS_AS_STRINGS = "decimalsAsStrings";
    public static final String ALLOW_EMPTY = "allowEmpty";
    public static final String EMPTY_RESULT_ERROR_CODE = "emptyResultErrorCode";
    public static final String RETURN_GENERATED_KEYS = "returnGeneratedKeys";
    public static final String GENERATED_KEY_COLUMNS = "generatedKeyColumns";

    /** Input names the query connector reads itself; every other input is a named SQL parameter. */
    public static final Set<String> QUERY_RESERVED = Set.of(
            SQL,
            DATASOURCE,
            PARAMETERS,
            PARAMETER_TYPES,
            TIMEOUT,
            MAX_ROWS,
            TRUNCATE,
            COLUMN_CASE,
            RESULT_FORMAT,
            RESULT_AS,
            DECIMALS_AS_STRINGS,
            ALLOW_EMPTY,
            EMPTY_RESULT_ERROR_CODE);

    /** Input names the update connector reads itself; every other input is a named SQL parameter. */
    public static final Set<String> UPDATE_RESERVED = Set.of(
            SQL,
            DATASOURCE,
            PARAMETERS,
            PARAMETER_TYPES,
            TIMEOUT,
            COLUMN_CASE,
            RETURN_GENERATED_KEYS,
            GENERATED_KEY_COLUMNS);

    private JdbcParameters() {}

    /**
     * Collects the SQL parameter values: the entries of the {@code parameters} map plus every input that is not a
     * reserved name. A name in both is an error.
     */
    public static Map<String, Object> sqlParameters(Map<String, Object> inputs, Set<String> reserved) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object map = inputs.get(PARAMETERS);
        if (map != null) {
            values.putAll(asMap(PARAMETERS, map));
        }
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            if (!reserved.contains(e.getKey())) {
                if (values.containsKey(e.getKey())) {
                    throw new JdbcConnectorException(
                            "SQL parameter '" + e.getKey() + "' is given both as an input and in the 'parameters' map");
                }
                values.put(e.getKey(), e.getValue());
            }
        }
        return values;
    }

    public static Map<String, SqlTypeHint> types(Object value) {
        Map<String, SqlTypeHint> types = new LinkedHashMap<>();
        if (value != null) {
            asMap(PARAMETER_TYPES, value).forEach((k, v) -> types.put(k, SqlTypeHint.parse(k, v)));
        }
        return types;
    }

    public static Map<String, Object> asMap(String what, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        throw new JdbcConnectorException(
                "'" + what + "' must be a map, but is " + value.getClass().getName());
    }

    public static boolean bool(String what, Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        if (text.equals("true")) {
            return true;
        }
        if (text.equals("false")) {
            return false;
        }
        throw new JdbcConnectorException("'" + what + "' must be true or false, but is '" + value + "'");
    }

    public static Integer integer(String what, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            if (n.longValue() != n.doubleValue()
                    || n.longValue() > Integer.MAX_VALUE
                    || n.longValue() < Integer.MIN_VALUE) {
                throw new JdbcConnectorException("'" + what + "' must be an integer, but is " + value);
            }
            return n.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new JdbcConnectorException("'" + what + "' must be an integer, but is '" + value + "'", e);
        }
    }

    public static List<String> stringList(String what, Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.util.Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        if (value instanceof String s) {
            return java.util.Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toList();
        }
        throw new JdbcConnectorException("'" + what + "' must be a list of names or a comma separated string");
    }
}
