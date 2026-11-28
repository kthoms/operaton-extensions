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

import java.util.HashMap;
import java.util.Map;
import org.operaton.connect.spi.Connector;
import org.operaton.connect.spi.ConnectorRequest;

/**
 * Request of the {@value JdbcUpdateConnector#ID} connector: a map of input parameters, see
 * {@link org.operaton.extensions.connector.jdbc.JdbcParameters}. Not thread-safe; one request per call.
 */
public class JdbcUpdateRequest implements ConnectorRequest<JdbcUpdateResponse> {

    private final JdbcUpdateConnector connector;
    private Map<String, Object> parameters = new HashMap<>();

    JdbcUpdateRequest(JdbcUpdateConnector connector) {
        this.connector = connector;
    }

    @Override
    public void setRequestParameters(Map<String, Object> requestParameters) {
        this.parameters = new HashMap<>(requestParameters);
    }

    @Override
    public void setRequestParameter(String name, Object value) {
        parameters.put(name, value);
    }

    @Override
    public Map<String, Object> getRequestParameters() {
        return parameters;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V getRequestParameter(String name) {
        return (V) parameters.get(name);
    }

    @Override
    public JdbcUpdateResponse execute() {
        return connector.execute(this);
    }

    Connector<?> connector() {
        return connector;
    }
}
