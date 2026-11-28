/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import org.operaton.extensions.connector.aillm.internal.AgentLoop;
import org.operaton.extensions.connector.aillm.internal.ChatModelFactory;
import org.operaton.extensions.connector.aillm.internal.ConfiguredModel;
import org.operaton.extensions.connector.aillm.internal.JsonSchemaCheck;
import org.operaton.extensions.connector.aillm.internal.PromptTemplate;
import org.operaton.extensions.connector.aillm.internal.Values;
import org.operaton.extensions.connector.aillm.tool.LlmTool;
import org.operaton.extensions.connector.aillm.tool.LlmToolContext;
import org.operaton.extensions.connector.aillm.tool.ProcessVariableTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three operations behind the connectors and delegates: {@link #prompt}, {@link #extract} and
 * {@link #agent}. Built once per engine from the configured models and tools ({@link AiLlmPlugin});
 * immutable and thread-safe, so the job executor may call it concurrently.
 */
public final class LlmService {

    private static final Logger LOG = LoggerFactory.getLogger(LlmService.class);

    /** Upper bound for {@code retries} of {@code llm-extract}. */
    public static final int MAX_EXTRACT_RETRIES = 10;

    /** Upper bound for {@code maxIterations} of {@code llm-agent}. */
    public static final int MAX_AGENT_ITERATIONS = 50;

    private final Map<String, ConfiguredModel> models;
    private final String defaultModel;
    private final int extractRetries;
    private final int agentMaxIterations;
    private final Map<String, LlmTool> tools;

    /**
     * Validates the configuration and builds the chat models. No provider is called here.
     *
     * @param models the named models; at least one
     * @param defaultModel the model used when a call names none
     * @param extractRetries default number of retries of {@code llm-extract} after an invalid answer
     * @param agentMaxIterations default maximum number of model calls of {@code llm-agent}
     * @param tools the tools agents may enable by name
     * @throws AiLlmException on any invalid value
     */
    public LlmService(
            Map<String, LlmModelConfig> models,
            String defaultModel,
            int extractRetries,
            int agentMaxIterations,
            List<LlmTool> tools) {
        this(models, defaultModel, extractRetries, agentMaxIterations, tools, ChatModelFactory::create);
    }

    LlmService(
            Map<String, LlmModelConfig> models,
            String defaultModel,
            int extractRetries,
            int agentMaxIterations,
            List<LlmTool> tools,
            Function<LlmModelConfig, dev.langchain4j.model.chat.ChatModel> factory) {
        if (models == null || models.isEmpty()) {
            throw new AiLlmException("No model is configured (operaton.extensions.connector.ai-llm.models.<name>.*)");
        }
        Map<String, ConfiguredModel> built = new LinkedHashMap<>();
        for (Map.Entry<String, LlmModelConfig> entry : models.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new AiLlmException("A model is configured without a name");
            }
            LlmModelConfig config = entry.getValue();
            if (config == null) {
                throw new AiLlmException("Model '" + name + "' has no configuration");
            }
            config.validate(name);
            built.put(name, new ConfiguredModel(name, config, factory.apply(config)));
        }
        this.models = Collections.unmodifiableMap(built);
        if (defaultModel == null || defaultModel.isBlank()) {
            throw new AiLlmException("default-model must be set");
        }
        if (!built.containsKey(defaultModel)) {
            throw new AiLlmException(
                    "default-model '" + defaultModel + "' is not configured; configured models: " + built.keySet());
        }
        this.defaultModel = defaultModel;
        this.extractRetries = checkRetries(extractRetries, "extract-retries");
        this.agentMaxIterations = checkIterations(agentMaxIterations, "agent-max-iterations");
        Map<String, LlmTool> toolMap = new LinkedHashMap<>();
        for (LlmTool tool : tools == null ? List.<LlmTool>of() : tools) {
            if (ProcessVariableTool.NAME.equals(tool.name())) {
                throw new AiLlmException("The tool name '" + ProcessVariableTool.NAME + "' is reserved");
            }
            if (toolMap.put(tool.name(), tool) != null) {
                throw new AiLlmException("Two tools are registered as '" + tool.name() + "'");
            }
        }
        this.tools = Collections.unmodifiableMap(toolMap);
        LOG.info(
                "AI LLM connector ready: models {} (default '{}'), tools {}",
                built.keySet(),
                defaultModel,
                toolMap.keySet());
    }

    /** Names of the configured models. */
    public List<String> modelNames() {
        return List.copyOf(models.keySet());
    }

    /** Names of the registered tools. */
    public List<String> toolNames() {
        return List.copyOf(tools.keySet());
    }

    /** The model a call without {@code model} uses. */
    public String defaultModel() {
        return defaultModel;
    }

    /**
     * {@code llm-prompt}: renders the prompts and returns the model's text answer.
     *
     * @param variables values for the prompt placeholders
     */
    public LlmTextResult prompt(LlmPromptRequest request, VariableSource variables) {
        ConfiguredModel model = model(request.model());
        List<ChatMessage> messages = messages(request, variables, null);
        ChatResponse response = model.chat(messages, parameters(request).build());
        String text = response.aiMessage().text();
        if (text == null) {
            throw new AiLlmException("Model '" + model.name() + "' returned no text"
                    + (response.aiMessage().hasToolExecutionRequests() ? " but tool calls" : ""));
        }
        return new LlmTextResult(
                text,
                model.reportedModelName(response),
                response.finishReason() == null ? null : response.finishReason().name(),
                LlmUsage.NONE.plus(response.tokenUsage()));
    }

    /**
     * {@code llm-extract}: asks for a JSON document valid against {@code jsonSchema}. An invalid
     * answer is sent back with the problems found, up to {@code retries} times; then the call fails
     * with {@link LlmOutputValidationException}.
     *
     * @param jsonSchema the JSON Schema as JSON text
     * @param retries retries after an invalid answer, {@code null} for the configured default
     */
    public LlmExtractResult extract(
            LlmPromptRequest request, String jsonSchema, Integer retries, VariableSource variables) {
        int allowedRetries = retries == null ? extractRetries : checkRetries(retries, "retries");
        JsonSchemaCheck schema = JsonSchemaCheck.compile(jsonSchema);
        ConfiguredModel model = model(request.model());
        String instructions = "Answer with a single JSON document only: no explanation and no Markdown code fence. "
                + "The JSON document must be valid against this JSON Schema:\n" + schema.schemaText();
        List<ChatMessage> messages = messages(request, variables, instructions);
        ChatRequestParameters built = switch (model.config().structuredOutputMode()) {
            case PROMPT -> parameters(request).build();
            case JSON_OBJECT ->
                parameters(request).responseFormat(ResponseFormat.JSON).build();
            case JSON_SCHEMA ->
                OpenAiChatRequestParameters.builder()
                        .overrideWith(parameters(request).build())
                        // sent as a raw parameter: LangChain4j's own schema parsing registers every
                        // Jackson module on the class path, and the Scala module of the engine's FEEL
                        // implementation turns the schema's arrays into Scala collections
                        .customParameters(Map.of("response_format", jsonSchemaResponseFormat(schema)))
                        .build();
        };
        LlmUsage usage = LlmUsage.NONE;
        List<String> problems = List.of();
        for (int attempt = 1; attempt <= allowedRetries + 1; attempt++) {
            ChatResponse response = model.chat(messages, built);
            usage = usage.plus(response.tokenUsage());
            String answer = response.aiMessage().text();
            JsonSchemaCheck.Outcome outcome = schema.check(answer);
            if (outcome.valid()) {
                return new LlmExtractResult(
                        outcome.json(), outcome.value(), attempt, model.reportedModelName(response), usage);
            }
            problems = outcome.problems();
            LOG.debug("Model '{}' answer {} is not valid: {}", model.name(), attempt, problems);
            messages.add(AiMessage.from(answer == null || answer.isEmpty() ? "(empty)" : answer));
            messages.add(UserMessage.from("Your answer was rejected: " + String.join("; ", problems)
                    + ". Answer again with only a JSON document that is valid against the JSON Schema."));
        }
        throw new LlmOutputValidationException(allowedRetries + 1, problems);
    }

    /**
     * {@code llm-agent}: lets the model call the enabled tools until it answers, at most
     * {@code maxIterations} model calls.
     *
     * @param toolNames registered tools to enable, may be empty
     * @param readableVariables variables the built-in {@value ProcessVariableTool#NAME} tool may read;
     *     empty disables it
     * @param maxIterations maximum number of model calls, {@code null} for the configured default
     * @param templateVariables values for the prompt placeholders
     * @param processVariables the calling execution's variables (for {@value ProcessVariableTool#NAME});
     *     required when {@code readableVariables} is not empty
     */
    public LlmAgentResult agent(
            LlmPromptRequest request,
            List<String> toolNames,
            List<String> readableVariables,
            Integer maxIterations,
            VariableSource templateVariables,
            VariableSource processVariables) {
        int iterations = maxIterations == null ? agentMaxIterations : checkIterations(maxIterations, "maxIterations");
        List<LlmTool> enabled = new ArrayList<>();
        for (String name : toolNames == null ? List.<String>of() : toolNames) {
            LlmTool tool = tools.get(name);
            if (tool == null) {
                throw new AiLlmException(
                        "Tool '" + name + "' is not registered; registered tools: " + new TreeSet<>(tools.keySet()));
            }
            enabled.add(tool);
        }
        if (readableVariables != null && !readableVariables.isEmpty()) {
            if (processVariables == null) {
                throw new AiLlmException("readableVariables needs a process execution; none is available here");
            }
            enabled.add(new ProcessVariableTool(readableVariables));
        }
        ConfiguredModel model = model(request.model());
        List<ChatMessage> messages = messages(request, templateVariables, null);
        LlmToolContext context =
                new LlmToolContext(processVariables == null ? VariableSource.none() : processVariables);
        return new AgentLoop(model, enabled, iterations, context)
                .run(messages, parameters(request).build());
    }

    private static Map<String, Object> jsonSchemaResponseFormat(JsonSchemaCheck schema) {
        Object parsed;
        try {
            parsed = Values.JSON.readValue(schema.schemaText(), Map.class);
        } catch (JsonProcessingException e) {
            throw new AiLlmException("jsonSchema cannot be read: " + e.getOriginalMessage(), e);
        }
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", "result");
        jsonSchema.put("strict", false);
        jsonSchema.put("schema", parsed);
        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("json_schema", jsonSchema);
        return format;
    }

    private ConfiguredModel model(String name) {
        String effective = name == null || name.isBlank() ? defaultModel : name;
        ConfiguredModel model = models.get(effective);
        if (model == null) {
            throw new AiLlmException(
                    "Model '" + effective + "' is not configured; configured models: " + models.keySet());
        }
        return model;
    }

    private static List<ChatMessage> messages(
            LlmPromptRequest request, VariableSource variables, String extraSystemInstructions) {
        VariableSource source = variables == null ? VariableSource.none() : variables;
        List<ChatMessage> messages = new ArrayList<>();
        String system = PromptTemplate.render(request.systemPrompt(), source);
        if (extraSystemInstructions != null) {
            system = system == null || system.isBlank()
                    ? extraSystemInstructions
                    : system + "\n\n" + extraSystemInstructions;
        }
        if (system != null && !system.isBlank()) {
            messages.add(SystemMessage.from(system));
        }
        String user = PromptTemplate.render(request.userPrompt(), source);
        if (user.isBlank()) {
            throw new AiLlmException("userPrompt is empty after rendering its placeholders");
        }
        messages.add(UserMessage.from(user));
        return messages;
    }

    private static DefaultChatRequestParameters.Builder<?> parameters(LlmPromptRequest request) {
        DefaultChatRequestParameters.Builder<?> builder = ChatRequestParameters.builder();
        if (request.temperature() != null) {
            builder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            builder.maxOutputTokens(request.maxTokens());
        }
        return builder;
    }

    private static int checkRetries(int value, String name) {
        if (value < 0 || value > MAX_EXTRACT_RETRIES) {
            throw new AiLlmException(name + " must be between 0 and " + MAX_EXTRACT_RETRIES + ", was " + value);
        }
        return value;
    }

    private static int checkIterations(int value, String name) {
        if (value < 1 || value > MAX_AGENT_ITERATIONS) {
            throw new AiLlmException(name + " must be between 1 and " + MAX_AGENT_ITERATIONS + ", was " + value);
        }
        return value;
    }
}
