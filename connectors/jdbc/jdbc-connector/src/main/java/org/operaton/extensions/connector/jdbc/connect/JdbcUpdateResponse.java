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

import java.util.List;
import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorResponse;

/**
 * Response of the {@value JdbcUpdateConnector#ID} connector. Output parameters: {@code updateCount},
 * {@code generatedKeys} (list of rows, empty unless requested) and {@code generatedKey} (the first column of the first
 * generated row, {@code null} if none).
 */
public class JdbcUpdateResponse extends AbstractConnectorResponse {

    public static final String UPDATE_COUNT = "updateCount";
    public static final String GENERATED_KEYS = "generatedKeys";
    public static final String GENERATED_KEY = "generatedKey";

    private final int updateCount;
    private final List<Map<String, Object>> generatedKeys;

    JdbcUpdateResponse(int updateCount, List<Map<String, Object>> generatedKeys) {
        this.updateCount = updateCount;
        this.generatedKeys = generatedKeys;
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.put(UPDATE_COUNT, updateCount);
        responseParameters.put(GENERATED_KEYS, generatedKeys);
        responseParameters.put(
                GENERATED_KEY,
                generatedKeys.isEmpty()
                        ? null
                        : generatedKeys.get(0).values().iterator().next());
    }
}
