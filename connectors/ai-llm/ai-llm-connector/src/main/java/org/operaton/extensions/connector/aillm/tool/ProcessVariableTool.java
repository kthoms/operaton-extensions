/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.List;
import java.util.Set;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.internal.Values;

/**
 * Built-in tool {@value #NAME}: reads one process variable, restricted to the names the task allows
 * ({@code readableVariables}). Created per call; immutable.
 */
public final class ProcessVariableTool implements LlmTool {

    /** The tool name shown to the model. */
    public static final String NAME = "read_process_variable";

    private final List<String> allowed;
    private final ToolSpecification specification;

    public ProcessVariableTool(List<String> allowedVariables) {
        if (allowedVariables == null || allowedVariables.isEmpty()) {
            throw new AiLlmException(NAME + " needs at least one readable variable");
        }
        this.allowed =
                List.copyOf(Set.copyOf(allowedVariables)).stream().sorted().toList();
        this.specification = ToolSpecification.builder()
                .name(NAME)
                .description("Reads the current value of a process variable. Returns the value as JSON or text.")
                .parameters(JsonObjectSchema.builder()
                        .addProperty(
                                "name",
                                JsonEnumSchema.builder()
                                        .enumValues(allowed)
                                        .description("Name of the variable")
                                        .build())
                        .required("name")
                        .build())
                .build();
    }

    @Override
    public ToolSpecification specification() {
        return specification;
    }

    /** The variable names this tool may read. */
    public List<String> allowedVariables() {
        return allowed;
    }

    @Override
    public String execute(String argumentsJson, LlmToolContext context) {
        String name;
        try {
            JsonNode arguments = Values.JSON.readTree(argumentsJson == null ? "{}" : argumentsJson);
            JsonNode nameNode = arguments.get("name");
            if (nameNode == null || !nameNode.isTextual()) {
                throw new ToolRefusedException("Argument 'name' (string) is required. Readable variables: " + allowed);
            }
            name = nameNode.asText();
        } catch (JsonProcessingException e) {
            throw new ToolRefusedException("The arguments are not a JSON object: " + e.getOriginalMessage());
        }
        if (!allowed.contains(name)) {
            throw new ToolRefusedException("Variable '" + name + "' may not be read. Readable variables: " + allowed);
        }
        if (!context.variables().has(name)) {
            return "Variable '" + name + "' is not set.";
        }
        return Values.render(context.variables().get(name));
    }
}
