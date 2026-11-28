/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.operaton.bpm.engine.delegate.VariableScope;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.connector.aillm.internal.LongText;
import org.operaton.extensions.connector.aillm.internal.Values;

/**
 * The three operations with their input and output parameter names, shared by the connectors and
 * the delegates so both accept exactly the same inputs. Stateless.
 */
public enum LlmOperation {

    /** {@code llm-prompt}: text answer. */
    PROMPT("llm-prompt"),

    /** {@code llm-extract}: JSON answer valid against a JSON Schema. */
    EXTRACT("llm-extract"),

    /** {@code llm-agent}: tool loop. */
    AGENT("llm-agent");

    // inputs
    public static final String MODEL = "model";
    public static final String SYSTEM_PROMPT = "systemPrompt";
    public static final String USER_PROMPT = "userPrompt";
    public static final String TEMPLATE_VARIABLES = "templateVariables";
    public static final String TEMPERATURE = "temperature";
    public static final String MAX_TOKENS = "maxTokens";
    public static final String RESULT_VARIABLE = "resultVariable";
    public static final String JSON_SCHEMA = "jsonSchema";
    public static final String RETRIES = "retries";
    public static final String RESULT_FORMAT = "resultFormat";
    public static final String TOOLS = "tools";
    public static final String READABLE_VARIABLES = "readableVariables";
    public static final String MAX_ITERATIONS = "maxIterations";

    // outputs
    public static final String TEXT = "text";
    public static final String RESULT = "result";
    public static final String RESULT_JSON = "resultJson";
    public static final String ATTEMPTS = "attempts";
    public static final String ITERATIONS = "iterations";
    public static final String TOOL_CALLS = "toolCalls";
    public static final String MODEL_NAME = "modelName";
    public static final String FINISH_REASON = "finishReason";
    public static final String INPUT_TOKENS = "inputTokens";
    public static final String OUTPUT_TOKENS = "outputTokens";
    public static final String TOTAL_TOKENS = "totalTokens";
    public static final String MODEL_CALLS = "modelCalls";

    private static final Set<String> COMMON_INPUTS =
            Set.of(MODEL, SYSTEM_PROMPT, USER_PROMPT, TEMPLATE_VARIABLES, TEMPERATURE, MAX_TOKENS, RESULT_VARIABLE);

    private final String connectorId;

    LlmOperation(String connectorId) {
        this.connectorId = connectorId;
    }

    /** The Connect connector id. */
    public String connectorId() {
        return connectorId;
    }

    /** The input parameter names this operation accepts. */
    public Set<String> inputs() {
        Set<String> inputs = new LinkedHashSet<>(COMMON_INPUTS);
        switch (this) {
            case PROMPT -> {
                // common inputs only
            }
            case EXTRACT -> inputs.addAll(List.of(JSON_SCHEMA, RETRIES, RESULT_FORMAT));
            case AGENT -> inputs.addAll(List.of(TOOLS, READABLE_VARIABLES, MAX_ITERATIONS));
        }
        return inputs;
    }

    /**
     * Runs the operation.
     *
     * @param inputs input parameters by name; {@code null} values count as absent
     * @param service the engine's service
     * @param execution the calling execution, {@code null} outside one
     * @param configuration the engine configuration (for storing long text), {@code null} outside an engine
     * @return the output parameters
     */
    public Map<String, Object> execute(
            Map<String, Object> inputs,
            LlmService service,
            VariableScope execution,
            ProcessEngineConfigurationImpl configuration) {
        checkInputs(inputs);
        LlmPromptRequest request = new LlmPromptRequest(
                string(inputs, MODEL),
                string(inputs, SYSTEM_PROMPT),
                string(inputs, USER_PROMPT),
                decimal(inputs, TEMPERATURE),
                integer(inputs, MAX_TOKENS));
        VariableSource executionVariables = execution == null ? null : VariableSource.of(execution);
        VariableSource templateVariables = templateVariables(inputs, executionVariables);
        String resultVariable = string(inputs, RESULT_VARIABLE);
        Map<String, Object> outputs = new LinkedHashMap<>();
        Object resultVariableValue;
        LlmUsage usage;
        switch (this) {
            case PROMPT -> {
                LlmTextResult result = service.prompt(request, templateVariables);
                outputs.put(TEXT, result.text());
                outputs.put(MODEL_NAME, result.modelName());
                outputs.put(FINISH_REASON, result.finishReason());
                usage = result.usage();
                resultVariableValue = resultVariable == null ? null : LongText.textValue(result.text(), configuration);
            }
            case EXTRACT -> {
                String format = resultFormat(inputs);
                LlmExtractResult result =
                        service.extract(request, schema(inputs), integer(inputs, RETRIES), templateVariables);
                outputs.put(RESULT, result.value());
                outputs.put(RESULT_JSON, result.json());
                outputs.put(ATTEMPTS, result.attempts());
                outputs.put(MODEL_NAME, result.modelName());
                usage = result.usage();
                resultVariableValue = resultVariable == null
                        ? null
                        : "string".equals(format)
                                ? LongText.textValue(result.json(), configuration)
                                : LongText.jsonValue(result.value(), configuration);
            }
            case AGENT -> {
                LlmAgentResult result = service.agent(
                        request,
                        names(inputs, TOOLS),
                        names(inputs, READABLE_VARIABLES),
                        integer(inputs, MAX_ITERATIONS),
                        templateVariables,
                        executionVariables);
                outputs.put(TEXT, result.text());
                outputs.put(ITERATIONS, result.iterations());
                outputs.put(TOOL_CALLS, result.toolCalls());
                outputs.put(MODEL_NAME, result.modelName());
                usage = result.usage();
                resultVariableValue = resultVariable == null ? null : LongText.textValue(result.text(), configuration);
            }
            default -> throw new IllegalStateException("unknown operation " + this);
        }
        outputs.put(INPUT_TOKENS, usage.inputTokens());
        outputs.put(OUTPUT_TOKENS, usage.outputTokens());
        outputs.put(TOTAL_TOKENS, usage.totalTokens());
        outputs.put(MODEL_CALLS, usage.modelCalls());
        if (resultVariable != null) {
            if (execution == null) {
                throw new AiLlmException(RESULT_VARIABLE + " needs a process execution; none is available here");
            }
            execution.setVariable(resultVariable, resultVariableValue);
        }
        return outputs;
    }

    private void checkInputs(Map<String, Object> inputs) {
        Set<String> accepted = inputs();
        for (String name : inputs.keySet()) {
            String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (normalized.contains("apikey")) {
                throw new AiLlmException(
                        "'" + name + "' is not accepted: API keys are configured per model "
                                + "(operaton.extensions.connector.ai-llm.models.<name>.api-key) and never passed as process data");
            }
            if (!accepted.contains(name)) {
                throw new AiLlmException(
                        "Unknown input parameter '" + name + "' for " + connectorId + "; accepted: " + accepted);
            }
        }
    }

    private static VariableSource templateVariables(Map<String, Object> inputs, VariableSource executionVariables) {
        Object value = inputs.get(TEMPLATE_VARIABLES);
        if (value == null) {
            return executionVariables == null ? VariableSource.none() : executionVariables;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return VariableSource.of(copy);
        }
        throw new AiLlmException(
                TEMPLATE_VARIABLES + " must be a map, was " + value.getClass().getName());
    }

    private static String resultFormat(Map<String, Object> inputs) {
        String format = string(inputs, RESULT_FORMAT);
        if (format == null) {
            return "json";
        }
        if (!format.equals("json") && !format.equals("string")) {
            throw new AiLlmException(RESULT_FORMAT + " must be 'json' or 'string', was '" + format + "'");
        }
        return format;
    }

    private static String schema(Map<String, Object> inputs) {
        Object value = inputs.get(JSON_SCHEMA);
        if (value == null) {
            throw new AiLlmException(JSON_SCHEMA + " is required for llm-extract");
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map<?, ?>) {
            return Values.render(value);
        }
        // e.g. a Spin JSON node, whose string form is its JSON
        return value.toString();
    }

    private static String string(Map<String, Object> inputs, String name) {
        Object value = inputs.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            if (text.isBlank()) {
                return null;
            }
            // names are trimmed, prompt texts are taken as they are
            return isName(name) ? text.trim() : text;
        }
        throw new AiLlmException(
                name + " must be a string, was " + value.getClass().getName());
    }

    private static boolean isName(String parameter) {
        return parameter.equals(MODEL) || parameter.equals(RESULT_VARIABLE) || parameter.equals(RESULT_FORMAT);
    }

    private static Integer integer(Map<String, Object> inputs, String name) {
        Object value = inputs.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return Math.toIntExact(((Number) value).longValue());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new AiLlmException(name + " must be an integer, was '" + text + "'", e);
            }
        }
        throw new AiLlmException(name + " must be an integer, was " + value);
    }

    private static Double decimal(Map<String, Object> inputs, String name) {
        Object value = inputs.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException e) {
                throw new AiLlmException(name + " must be a number, was '" + text + "'", e);
            }
        }
        throw new AiLlmException(name + " must be a number, was " + value);
    }

    /** A list of names: a collection of strings or one comma-separated string. */
    private static List<String> names(Map<String, Object> inputs, String name) {
        Object value = inputs.get(name);
        if (value == null) {
            return List.of();
        }
        Collection<?> items;
        if (value instanceof String text) {
            items = Arrays.asList(text.split(","));
        } else if (value instanceof Collection<?> collection) {
            items = collection;
        } else {
            throw new AiLlmException(name + " must be a list or a comma-separated string, was "
                    + value.getClass().getName());
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text)) {
                throw new AiLlmException(name + " must contain strings, found " + item);
            }
            if (!text.isBlank()) {
                result.add(text.trim());
            }
        }
        return result;
    }
}
