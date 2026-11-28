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
import org.operaton.extensions.connector.jdbc.JdbcOperations;
import org.operaton.extensions.connector.jdbc.JdbcParameters;

/**
 * Java delegate alternative to the {@code jdbc-update} connector. Fields: {@code sql} (literal), {@code datasource},
 * {@code parameters}, {@code parameterTypes}, {@code timeout}, {@code columnCase}, {@code returnGeneratedKeys},
 * {@code generatedKeyColumns}, and the optional result variable names {@code updateCountVariable},
 * {@code generatedKeyVariable} and {@code generatedKeysVariable}. A generated-key variable implies
 * {@code returnGeneratedKeys}.
 */
public class JdbcUpdateDelegate extends AbstractJdbcDelegate {

    protected Expression returnGeneratedKeys;
    protected Expression generatedKeyColumns;
    protected Expression updateCountVariable;
    protected Expression generatedKeyVariable;
    protected Expression generatedKeysVariable;

    @Override
    public void execute(DelegateExecution execution) {
        String sqlText = literalSql();
        Object cc = eval(columnCase, execution);
        boolean keys =
                JdbcParameters.bool(JdbcParameters.RETURN_GENERATED_KEYS, eval(returnGeneratedKeys, execution), false)
                        || generatedKeyVariable != null
                        || generatedKeysVariable != null;
        var update = new JdbcOperations.Update(
                JdbcQueryDelegate.text(eval(datasource, execution)),
                sqlText,
                sqlParameters(sqlText, execution),
                JdbcParameters.types(eval(parameterTypes, execution)),
                JdbcParameters.integer(JdbcParameters.TIMEOUT, eval(timeout, execution)),
                keys,
                JdbcParameters.stringList(JdbcParameters.GENERATED_KEY_COLUMNS, eval(generatedKeyColumns, execution)),
                cc == null ? ColumnCase.AS_IS : ColumnCase.parse(cc));
        JdbcOperations.UpdateResult result = operations().update(update);
        if (updateCountVariable != null) {
            execution.setVariable(JdbcQueryDelegate.text(eval(updateCountVariable, execution)), result.updateCount());
        }
        if (generatedKeyVariable != null) {
            execution.setVariable(
                    JdbcQueryDelegate.text(eval(generatedKeyVariable, execution)),
                    result.generatedKeys().isEmpty()
                            ? null
                            : result.generatedKeys().get(0).values().iterator().next());
        }
        if (generatedKeysVariable != null) {
            execution.setVariable(
                    JdbcQueryDelegate.text(eval(generatedKeysVariable, execution)), result.generatedKeys());
        }
    }
}
