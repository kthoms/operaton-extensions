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
import org.operaton.extensions.connector.jdbc.JdbcResults;
import org.operaton.extensions.connector.jdbc.ResultFormat;

/**
 * Connector {@value #ID}: runs a SQL query and returns its rows. Inputs and outputs are documented in the README. The
 * instance is shared by all process instances and keeps no state; the data sources come from
 * {@link org.operaton.extensions.connector.jdbc.JdbcConnectors}.
 */
public class JdbcQueryConnector extends AbstractConnector<JdbcQueryRequest, JdbcQueryResponse> {

    public static final String ID = "jdbc-query";

    public JdbcQueryConnector() {
        super(ID);
    }

    @Override
    public JdbcQueryRequest createRequest() {
        return new JdbcQueryRequest(this);
    }

    @Override
    public JdbcQueryResponse execute(JdbcQueryRequest request) {
        Callable<Object> call = () -> run(request);
        try {
            return (JdbcQueryResponse) new JdbcCall(call, request, requestInterceptors).proceed();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new JdbcConnectorException("Connector " + ID + " failed: " + e.getMessage(), e);
        }
    }

    private JdbcQueryResponse run(JdbcQueryRequest request) {
        var in = request.getRequestParameters();
        var query = new JdbcOperations.Query(
                (String) toText(in.get(JdbcParameters.DATASOURCE)),
                (String) toText(in.get(JdbcParameters.SQL)),
                JdbcParameters.sqlParameters(in, JdbcParameters.QUERY_RESERVED),
                JdbcParameters.types(in.get(JdbcParameters.PARAMETER_TYPES)),
                JdbcParameters.integer(JdbcParameters.TIMEOUT, in.get(JdbcParameters.TIMEOUT)),
                JdbcParameters.integer(JdbcParameters.MAX_ROWS, in.get(JdbcParameters.MAX_ROWS)),
                JdbcParameters.bool(JdbcParameters.TRUNCATE, in.get(JdbcParameters.TRUNCATE), false),
                in.get(JdbcParameters.COLUMN_CASE) == null
                        ? ColumnCase.AS_IS
                        : ColumnCase.parse(in.get(JdbcParameters.COLUMN_CASE)));
        ResultFormat format = in.get(JdbcParameters.RESULT_FORMAT) == null
                ? ResultFormat.LIST
                : ResultFormat.parse(in.get(JdbcParameters.RESULT_FORMAT));
        boolean json = parseResultAs(in.get(JdbcParameters.RESULT_AS));

        JdbcOperations.QueryResult result = JdbcOperations.current().query(query);
        Object value = JdbcResults.shape(
                result,
                format,
                JdbcParameters.bool(JdbcParameters.ALLOW_EMPTY, in.get(JdbcParameters.ALLOW_EMPTY), false),
                (String) toText(in.get(JdbcParameters.EMPTY_RESULT_ERROR_CODE)),
                json,
                JdbcParameters.bool(
                        JdbcParameters.DECIMALS_AS_STRINGS, in.get(JdbcParameters.DECIMALS_AS_STRINGS), true));
        return new JdbcQueryResponse(value, result.rows().size(), result.truncated());
    }

    static boolean parseResultAs(Object value) {
        if (value == null) {
            return false;
        }
        String text = value.toString().trim();
        if (text.equalsIgnoreCase("json")) {
            return true;
        }
        if (text.equalsIgnoreCase("java")) {
            return false;
        }
        throw new JdbcConnectorException("Unknown resultAs '" + value + "'; supported: java, json");
    }

    static Object toText(Object value) {
        return value == null ? null : value.toString();
    }
}
