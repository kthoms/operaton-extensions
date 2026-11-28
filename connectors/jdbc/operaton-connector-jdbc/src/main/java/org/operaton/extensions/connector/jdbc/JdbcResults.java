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

import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.delegate.BpmnError;
import org.operaton.extensions.connector.jdbc.internal.JsonWriter;

/** Turns a {@link JdbcOperations.QueryResult} into the value stored in the process. Stateless. */
public final class JdbcResults {

    private JdbcResults() {}

    /**
     * @param format shape of the value
     * @param allowEmpty single-row formats: an empty result yields {@code null} instead of failing
     * @param errorCode if set, an empty result throws a {@link BpmnError} with this code (for modelled business errors,
     *     takes precedence over {@code allowEmpty})
     * @param json represent the value as Spin JSON (rows as an array of objects); needs Spin on the classpath
     * @param decimalsAsStrings JSON only: write decimals as strings (exact for every consumer) instead of JSON numbers
     */
    public static Object shape(
            JdbcOperations.QueryResult result,
            ResultFormat format,
            boolean allowEmpty,
            String errorCode,
            boolean json,
            boolean decimalsAsStrings) {
        List<Map<String, Object>> rows = result.rows();
        if (rows.isEmpty() && errorCode != null && !errorCode.isBlank()) {
            throw new BpmnError(errorCode, "The query returned no rows");
        }
        Object value;
        switch (format) {
            case LIST -> value = rows;
            case SINGLE_ROW -> {
                Map<String, Object> row = single(rows, allowEmpty);
                value = row;
            }
            case SINGLE_VALUE -> {
                Map<String, Object> row = single(rows, allowEmpty);
                if (row == null) {
                    return null;
                }
                if (row.size() != 1) {
                    throw new JdbcConnectorException("resultFormat SINGLE_VALUE needs exactly one column, but the query"
                            + " returned " + row.size() + ": " + result.columns());
                }
                return row.values().iterator().next();
            }
            default -> throw new IllegalStateException("unreachable: " + format);
        }
        if (value == null || !json) {
            return value;
        }
        return SpinJson.parse(JsonWriter.write(value, decimalsAsStrings));
    }

    private static Map<String, Object> single(List<Map<String, Object>> rows, boolean allowEmpty) {
        if (rows.size() == 1) {
            return rows.get(0);
        }
        if (rows.isEmpty()) {
            if (allowEmpty) {
                return null;
            }
            throw new JdbcConnectorException("The query returned no rows, but a single row was expected;"
                    + " set allowEmpty=true to accept an empty result");
        }
        throw new JdbcConnectorException("The query returned " + rows.size() + " rows, but a single row was expected");
    }
}
