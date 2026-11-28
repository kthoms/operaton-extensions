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

import java.util.Locale;

/** Shape of the {@code result} of a query. */
public enum ResultFormat {
    /** A list of rows, each a map from column label to value. An empty result is an empty list. */
    LIST,
    /** The single row as a map. Zero or several rows fail, unless {@code allowEmpty} permits zero rows (result null). */
    SINGLE_ROW,
    /** The single value of a one-row, one-column result. Same rules as {@link #SINGLE_ROW}. */
    SINGLE_VALUE;

    public static ResultFormat parse(Object value) {
        if (value instanceof ResultFormat f) {
            return f;
        }
        String text = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new JdbcConnectorException(
                    "Unknown resultFormat '" + value + "'; supported: " + java.util.Arrays.toString(values()));
        }
    }
}
