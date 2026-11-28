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

import java.util.concurrent.Callable;
import org.operaton.connect.impl.AbstractConnector;
import org.operaton.extensions.connector.jdbc.ColumnCase;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.JdbcOperations;
import org.operaton.extensions.connector.jdbc.JdbcParameters;

/**
 * Connector {@value #ID}: runs an INSERT, UPDATE, DELETE or DDL statement and returns the update count and, on request,
 * the generated keys. The instance is shared and keeps no state.
 */
public class JdbcUpdateConnector extends AbstractConnector<JdbcUpdateRequest, JdbcUpdateResponse> {

    public static final String ID = "jdbc-update";

    public JdbcUpdateConnector() {
        super(ID);
    }

    @Override
    public JdbcUpdateRequest createRequest() {
        return new JdbcUpdateRequest(this);
    }

    @Override
    public JdbcUpdateResponse execute(JdbcUpdateRequest request) {
        Callable<Object> call = () -> run(request);
        try {
            return (JdbcUpdateResponse) new JdbcCall(call, request, requestInterceptors).proceed();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new JdbcConnectorException("Connector " + ID + " failed: " + e.getMessage(), e);
        }
    }

    private JdbcUpdateResponse run(JdbcUpdateRequest request) {
        var in = request.getRequestParameters();
        var update = new JdbcOperations.Update(
                (String) JdbcQueryConnector.toText(in.get(JdbcParameters.DATASOURCE)),
                (String) JdbcQueryConnector.toText(in.get(JdbcParameters.SQL)),
                JdbcParameters.sqlParameters(in, JdbcParameters.UPDATE_RESERVED),
                JdbcParameters.types(in.get(JdbcParameters.PARAMETER_TYPES)),
                JdbcParameters.integer(JdbcParameters.TIMEOUT, in.get(JdbcParameters.TIMEOUT)),
                JdbcParameters.bool(
                        JdbcParameters.RETURN_GENERATED_KEYS, in.get(JdbcParameters.RETURN_GENERATED_KEYS), false),
                JdbcParameters.stringList(
                        JdbcParameters.GENERATED_KEY_COLUMNS, in.get(JdbcParameters.GENERATED_KEY_COLUMNS)),
                in.get(JdbcParameters.COLUMN_CASE) == null
                        ? ColumnCase.AS_IS
                        : ColumnCase.parse(in.get(JdbcParameters.COLUMN_CASE)));
        JdbcOperations.UpdateResult result = JdbcOperations.current().update(update);
        return new JdbcUpdateResponse(result.updateCount(), result.generatedKeys());
    }
}
