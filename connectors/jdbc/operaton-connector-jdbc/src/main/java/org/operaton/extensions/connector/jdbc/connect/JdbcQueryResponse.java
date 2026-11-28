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

import java.util.Map;
import org.operaton.connect.impl.AbstractConnectorResponse;

/**
 * Response of the {@value JdbcQueryConnector#ID} connector. Output parameters: {@code result} (shaped by
 * {@code resultFormat}), {@code rowCount} (rows in the result) and {@code truncated}.
 */
public class JdbcQueryResponse extends AbstractConnectorResponse {

    public static final String RESULT = "result";
    public static final String ROW_COUNT = "rowCount";
    public static final String TRUNCATED = "truncated";

    private final Object result;
    private final int rowCount;
    private final boolean truncated;

    JdbcQueryResponse(Object result, int rowCount, boolean truncated) {
        this.result = result;
        this.rowCount = rowCount;
        this.truncated = truncated;
    }

    @Override
    protected void collectResponseParameters(Map<String, Object> responseParameters) {
        responseParameters.put(RESULT, result);
        responseParameters.put(ROW_COUNT, rowCount);
        responseParameters.put(TRUNCATED, truncated);
    }
}
