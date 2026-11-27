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

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.JdbcOperations;
import org.operaton.extensions.connector.jdbc.JdbcParameters;
import org.operaton.extensions.connector.jdbc.NamedSqlParameters;

/**
 * Base of the JDBC delegates. Configure them with {@code operaton:field} (literal or expression). The {@code sql} field
 * must be a literal: a SQL text taken from an expression (and so possibly from user input) is rejected, which makes
 * SQL injection impossible by construction; values reach the statement only as bound parameters.
 *
 * <p>A SQL parameter is taken from the {@code parameters} field (an expression returning a map) and otherwise from the
 * process variable of the same name. A parameter found in neither makes the delegate fail. The engine creates one
 * delegate instance per activity and the instance keeps only its field expressions, so it is safe under the job
 * executor.
 */
abstract class AbstractJdbcDelegate implements JavaDelegate {

    protected Expression sql;
    protected Expression datasource;
    protected Expression parameters;
    protected Expression parameterTypes;
    protected Expression timeout;
    protected Expression columnCase;

    protected final String literalSql() {
        if (sql == null) {
            throw new JdbcConnectorException("Field 'sql' is required");
        }
        if (!sql.isLiteralText()) {
            throw new JdbcConnectorException(
                    "Field 'sql' must be literal text, not an expression: the SQL text must not"
                            + " depend on process data. Pass data through named parameters (:name) instead.");
        }
        return sql.getExpressionText();
    }

    protected final Object eval(Expression expression, DelegateExecution execution) {
        return expression == null ? null : expression.getValue(execution);
    }

    /** The named parameters: the {@code parameters} map, completed from process variables. */
    protected final Map<String, Object> sqlParameters(String sqlText, DelegateExecution execution) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object map = eval(parameters, execution);
        if (map != null) {
            values.putAll(JdbcParameters.asMap("parameters", map));
        }
        for (String name : NamedSqlParameters.namesOf(sqlText)) {
            if (!values.containsKey(name)) {
                if (!execution.hasVariable(name)) {
                    throw new JdbcConnectorException("SQL parameter ':" + name
                            + "' is neither in the 'parameters' field nor a process variable of activity '"
                            + execution.getCurrentActivityId() + "'");
                }
                values.put(name, execution.getVariable(name));
            }
        }
        return values;
    }

    protected final JdbcOperations operations() {
        return JdbcOperations.current();
    }
}
