/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.SchemaLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.operaton.extensions.connector.aillm.AiLlmException;

/**
 * A compiled JSON Schema and the checks of a model answer against it. Schemas without
 * {@code $schema} are read as draft 2020-12. References to remote schemas are refused: a schema is
 * process data, and resolving it must not make the engine fetch arbitrary URLs. Immutable and
 * thread-safe.
 */
public final class JsonSchemaCheck {

    private static final int MAX_REPORTED_PROBLEMS = 20;

    private static final Pattern CODE_FENCE =
            Pattern.compile("^\\s*```[A-Za-z0-9_-]*\\s*\\n(.*?)\\n?\\s*```\\s*$", Pattern.DOTALL);

    /** Refuses everything but the validator's own bundled meta-schemas. */
    private static final SchemaLoader NO_REMOTE_SCHEMAS = iri -> {
        if (iri.toString().startsWith("classpath:")) {
            return null;
        }
        throw new AiLlmException("JSON schema references '" + iri + "'; loading referenced schemas is not allowed, "
                + "inline them with $defs");
    };

    private final String schemaText;
    private final JsonSchema schema;

    private JsonSchemaCheck(String schemaText, JsonSchema schema) {
        this.schemaText = schemaText;
        this.schema = schema;
    }

    /** Compiles a schema given as JSON text; fails if it is not valid JSON or not a usable schema. */
    public static JsonSchemaCheck compile(String schemaText) {
        if (schemaText == null || schemaText.isBlank()) {
            throw new AiLlmException("jsonSchema is required");
        }
        JsonNode node;
        try {
            node = Values.JSON.readTree(schemaText);
        } catch (JsonProcessingException e) {
            throw new AiLlmException("jsonSchema is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (!node.isObject()) {
            throw new AiLlmException("jsonSchema must be a JSON object, was " + node.getNodeType());
        }
        try {
            SpecVersion.VersionFlag version =
                    SpecVersionDetector.detectOptionalVersion(node, true).orElse(SpecVersion.VersionFlag.V202012);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                    version, builder -> builder.schemaLoaders(loaders -> loaders.add(NO_REMOTE_SCHEMAS)));
            // English messages: they are sent back to the model
            JsonSchema compiled = factory.getSchema(
                    node,
                    SchemaValidatorsConfig.builder().locale(Locale.ENGLISH).build());
            compiled.initializeValidators();
            return new JsonSchemaCheck(Values.JSON.writeValueAsString(node), compiled);
        } catch (JsonSchemaException | JsonProcessingException e) {
            throw new AiLlmException("jsonSchema is not a usable JSON Schema: " + e.getMessage(), e);
        }
    }

    /** The schema as compact JSON text. */
    public String schemaText() {
        return schemaText;
    }

    /**
     * Parses a model answer (tolerating one surrounding Markdown code fence) and validates it.
     *
     * @return the outcome: the parsed document, or what is wrong with it
     */
    public Outcome check(String answer) {
        if (answer == null || answer.isBlank()) {
            return Outcome.invalid(List.of("the answer was empty"));
        }
        String text = answer.strip();
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.matches()) {
            text = fence.group(1).strip();
        }
        JsonNode document;
        try {
            document = Values.JSON.readTree(text);
        } catch (JsonProcessingException e) {
            return Outcome.invalid(List.of("the answer is not valid JSON: " + e.getOriginalMessage()));
        }
        Set<ValidationMessage> messages = schema.validate(document);
        if (!messages.isEmpty()) {
            List<String> problems = new ArrayList<>();
            for (ValidationMessage message : messages) {
                if (problems.size() == MAX_REPORTED_PROBLEMS) {
                    problems.add("... " + (messages.size() - MAX_REPORTED_PROBLEMS) + " more");
                    break;
                }
                problems.add(message.getMessage());
            }
            return Outcome.invalid(problems);
        }
        try {
            Object value = Values.JSON.treeToValue(document, Object.class);
            return new Outcome(Values.JSON.writeValueAsString(document), value, List.of());
        } catch (JsonProcessingException e) {
            throw new AiLlmException("Cannot convert the validated JSON answer: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Result of {@link #check(String)}.
     *
     * @param json normalized JSON text, {@code null} if invalid
     * @param value parsed value, {@code null} if invalid (or the JSON {@code null})
     * @param problems empty if valid
     */
    public record Outcome(String json, Object value, List<String> problems) {

        static Outcome invalid(List<String> problems) {
            return new Outcome(null, null, List.copyOf(problems));
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }
}
