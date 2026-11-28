/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.delegate;

import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.AiLlmPlugin;
import org.operaton.extensions.connector.aillm.LlmOperation;

/**
 * Base of the JavaDelegate alternatives to the connectors. Inputs are injected fields
 * ({@code <operaton:field name="userPrompt">}) with the same names as the connector inputs. The
 * result goes to {@code resultVariable} (required); the token usage to
 * {@code <resultVariable>InputTokens}, {@code <resultVariable>OutputTokens} and
 * {@code <resultVariable>TotalTokens}.
 *
 * <p>Use the delegates with {@code operaton:class}: the engine then creates an instance per call. Do
 * not register them as shared Spring beans, injected fields are per instance.
 */
public abstract class AbstractLlmDelegate implements JavaDelegate {

    protected Expression model;
    protected Expression systemPrompt;
    protected Expression userPrompt;
    protected Expression templateVariables;
    protected Expression temperature;
    protected Expression maxTokens;
    protected Expression resultVariable;

    /** The operation this delegate runs. */
    protected abstract LlmOperation operation();

    /** Adds the operation-specific fields to the inputs. */
    protected abstract void addOperationInputs(Map<String, Expression> fields);

    @Override
    public void execute(DelegateExecution execution) {
        Map<String, Expression> fields = new LinkedHashMap<>();
        fields.put(LlmOperation.MODEL, model);
        fields.put(LlmOperation.SYSTEM_PROMPT, systemPrompt);
        fields.put(LlmOperation.USER_PROMPT, userPrompt);
        fields.put(LlmOperation.TEMPLATE_VARIABLES, templateVariables);
        fields.put(LlmOperation.TEMPERATURE, temperature);
        fields.put(LlmOperation.MAX_TOKENS, maxTokens);
        fields.put(LlmOperation.RESULT_VARIABLE, resultVariable);
        addOperationInputs(fields);

        Map<String, Object> inputs = new LinkedHashMap<>();
        fields.forEach((name, expression) -> {
            if (expression != null) {
                Object value = expression.getValue(execution);
                if (value != null) {
                    inputs.put(name, value);
                }
            }
        });
        Object target = inputs.get(LlmOperation.RESULT_VARIABLE);
        if (!(target instanceof String name) || name.isBlank()) {
            throw new AiLlmException(getClass().getSimpleName() + " on activity '" + execution.getCurrentActivityId()
                    + "' needs the field resultVariable");
        }
        ProcessEngineConfigurationImpl configuration =
                (ProcessEngineConfigurationImpl) execution.getProcessEngine().getProcessEngineConfiguration();
        Map<String, Object> outputs =
                operation().execute(inputs, AiLlmPlugin.serviceOf(configuration), execution, configuration);
        String prefix = name.trim();
        execution.setVariable(prefix + "InputTokens", outputs.get(LlmOperation.INPUT_TOKENS));
        execution.setVariable(prefix + "OutputTokens", outputs.get(LlmOperation.OUTPUT_TOKENS));
        execution.setVariable(prefix + "TotalTokens", outputs.get(LlmOperation.TOTAL_TOKENS));
    }
}
