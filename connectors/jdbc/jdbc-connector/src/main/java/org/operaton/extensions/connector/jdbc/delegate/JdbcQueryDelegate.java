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
package org.operaton.extensions.connector.jdbc.delegate;

import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.extensions.connector.jdbc.ColumnCase;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.JdbcOperations;
import org.operaton.extensions.connector.jdbc.JdbcParameters;
import org.operaton.extensions.connector.jdbc.JdbcResults;
import org.operaton.extensions.connector.jdbc.ResultFormat;

/**
 * Java delegate alternative to the {@code jdbc-query} connector.
 *
 * <pre>{@code
 * <serviceTask id="lookup" name="Look up customer" operaton:class="org.operaton.extensions.connector.jdbc.delegate.JdbcQueryDelegate">
 *   <extensionElements>
 *     <operaton:field name="sql"><operaton:string>SELECT * FROM customer WHERE id = :customerId</operaton:string></operaton:field>
 *     <operaton:field name="resultFormat"><operaton:string>SINGLE_ROW</operaton:string></operaton:field>
 *     <operaton:field name="resultVariable"><operaton:string>customer</operaton:string></operaton:field>
 *   </extensionElements>
 * </serviceTask>
 * }</pre>
 *
 * Fields: {@code sql} (literal), {@code resultVariable} (required), {@code datasource}, {@code parameters},
 * {@code parameterTypes}, {@code timeout}, {@code maxRows}, {@code truncate}, {@code columnCase},
 * {@code resultFormat}, {@code resultAs}, {@code decimalsAsStrings}, {@code allowEmpty}, {@code emptyResultErrorCode},
 * {@code rowCountVariable}, {@code truncatedVariable}. Same semantics as the connector inputs.
 */
public class JdbcQueryDelegate extends AbstractJdbcDelegate {

    protected Expression resultVariable;
    protected Expression maxRows;
    protected Expression truncate;
    protected Expression resultFormat;
    protected Expression resultAs;
    protected Expression decimalsAsStrings;
    protected Expression allowEmpty;
    protected Expression emptyResultErrorCode;
    protected Expression rowCountVariable;
    protected Expression truncatedVariable;

    @Override
    public void execute(DelegateExecution execution) {
        if (resultVariable == null) {
            throw new JdbcConnectorException("Field 'resultVariable' is required");
        }
        String sqlText = literalSql();
        Object format = eval(resultFormat, execution);
        Object as = eval(resultAs, execution);
        Object cc = eval(columnCase, execution);
        var query = new JdbcOperations.Query(
                text(eval(datasource, execution)),
                sqlText,
                sqlParameters(sqlText, execution),
                JdbcParameters.types(eval(parameterTypes, execution)),
                JdbcParameters.integer(JdbcParameters.TIMEOUT, eval(timeout, execution)),
                JdbcParameters.integer(JdbcParameters.MAX_ROWS, eval(maxRows, execution)),
                JdbcParameters.bool(JdbcParameters.TRUNCATE, eval(truncate, execution), false),
                cc == null ? ColumnCase.AS_IS : ColumnCase.parse(cc));
        JdbcOperations.QueryResult result = operations().query(query);
        Object value = JdbcResults.shape(
                result,
                format == null ? ResultFormat.LIST : ResultFormat.parse(format),
                JdbcParameters.bool(JdbcParameters.ALLOW_EMPTY, eval(allowEmpty, execution), false),
                text(eval(emptyResultErrorCode, execution)),
                as != null && "json".equalsIgnoreCase(as.toString().trim()),
                JdbcParameters.bool(JdbcParameters.DECIMALS_AS_STRINGS, eval(decimalsAsStrings, execution), true));
        execution.setVariable(text(eval(resultVariable, execution)), value);
        if (rowCountVariable != null) {
            execution.setVariable(
                    text(eval(rowCountVariable, execution)), result.rows().size());
        }
        if (truncatedVariable != null) {
            execution.setVariable(text(eval(truncatedVariable, execution)), result.truncated());
        }
    }

    static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
