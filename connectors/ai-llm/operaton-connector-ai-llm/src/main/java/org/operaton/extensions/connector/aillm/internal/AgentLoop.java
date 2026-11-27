/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/connect/ai-agent/src/main/java/org/cibseven/connect/ai/agent/impl/AgentConnectorImpl.java (Apache-2.0)
 * Ported: the handling of a tool name the model made up (answer with the available tools instead of
 * failing), the split between a refused tool request (told to the model) and a failing tool
 * (rethrown so the job fails), and the message texts. Changes: an explicit loop over the LangChain4j
 * ChatModel instead of AiServices, a hard bound on model calls, no MCP, no chat memory.
 */
package org.operaton.extensions.connector.aillm.internal;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.LlmAgentIterationLimitException;
import org.operaton.extensions.connector.aillm.LlmAgentResult;
import org.operaton.extensions.connector.aillm.LlmUsage;
import org.operaton.extensions.connector.aillm.tool.LlmTool;
import org.operaton.extensions.connector.aillm.tool.LlmToolContext;
import org.operaton.extensions.connector.aillm.tool.ToolRefusedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agent's tool loop: call the model with the enabled tools, execute the tool calls it asks for,
 * send the results back, until it answers without tool calls or {@code maxIterations} model calls
 * have been made. One instance per invocation; not shared between threads.
 *
 * <ul>
 *   <li>A tool name the model made up is answered with the list of available tools; the model may
 *       correct itself in the next iteration.
 *   <li>A {@link ToolRefusedException} (bad arguments, a value that is not allowed) is answered with
 *       its message.
 *   <li>Any other exception from a tool ends the loop and fails the task: the tool may have changed
 *       state inside the engine transaction, and telling the model would let it work on in a
 *       transaction that can no longer commit.
 * </ul>
 */
public final class AgentLoop {

    private static final Logger LOG = LoggerFactory.getLogger(AgentLoop.class);

    private static final int MAX_NAME_CHARS = 100;

    private final ConfiguredModel model;
    private final Map<String, LlmTool> tools;
    private final List<ToolSpecification> specifications;
    private final int maxIterations;
    private final LlmToolContext toolContext;

    public AgentLoop(ConfiguredModel model, List<LlmTool> tools, int maxIterations, LlmToolContext toolContext) {
        this.model = model;
        this.tools = new LinkedHashMap<>();
        for (LlmTool tool : tools) {
            if (this.tools.put(tool.name(), tool) != null) {
                throw new AiLlmException("Two tools are named '" + tool.name() + "'");
            }
        }
        this.specifications = tools.stream().map(LlmTool::specification).toList();
        this.maxIterations = maxIterations;
        this.toolContext = toolContext;
    }

    public LlmAgentResult run(List<ChatMessage> initialMessages, ChatRequestParameters baseParameters) {
        List<ChatMessage> messages = new ArrayList<>(initialMessages);
        ChatRequestParameters parameters = specifications.isEmpty()
                ? baseParameters
                : baseParameters.overrideWith(ChatRequestParameters.builder()
                        .toolSpecifications(specifications)
                        .build());
        LlmUsage usage = LlmUsage.NONE;
        int toolCalls = 0;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            ChatResponse response = model.chat(messages, parameters);
            usage = usage.plus(response.tokenUsage());
            AiMessage answer = response.aiMessage();
            if (!answer.hasToolExecutionRequests()) {
                if (answer.text() == null || answer.text().isBlank()) {
                    throw new AiLlmException("Model '" + model.name() + "' ended the agent loop without an answer");
                }
                return new LlmAgentResult(
                        answer.text(), iteration, toolCalls, model.reportedModelName(response), usage);
            }
            messages.add(answer);
            for (ToolExecutionRequest request : answer.toolExecutionRequests()) {
                toolCalls++;
                messages.add(ToolExecutionResultMessage.from(request, execute(request)));
            }
        }
        throw new LlmAgentIterationLimitException(maxIterations, toolCalls);
    }

    private String execute(ToolExecutionRequest request) {
        LlmTool tool = tools.get(request.name());
        if (tool == null) {
            LOG.debug("Model '{}' called unknown tool '{}'", model.name(), shownName(request));
            return unknownToolMessage(request);
        }
        try {
            String result = tool.execute(request.arguments(), toolContext);
            LOG.debug("Tool '{}' answered ({} chars)", tool.name(), result == null ? 0 : result.length());
            return result == null ? "null" : result;
        } catch (ToolRefusedException refused) {
            LOG.debug("Tool '{}' refused the request: {}", tool.name(), refused.getMessage());
            String text = refused.getMessage();
            return text == null || text.isBlank() ? refused.getClass().getName() : text;
        } catch (RuntimeException e) {
            // No stack trace here: the engine logs the exception once it fails the job.
            LOG.warn(
                    "Tool '{}' failed with {}; the agent loop ends and the task fails",
                    tool.name(),
                    e.getClass().getName());
            throw e;
        }
    }

    private String unknownToolMessage(ToolExecutionRequest request) {
        String available = tools.isEmpty() ? "none" : String.join(", ", tools.keySet());
        return "There is no tool named '" + Values.abbreviate(shownName(request), MAX_NAME_CHARS)
                + "'. Available tools: " + available + ". Call one of these instead"
                + (tools.isEmpty() ? ", or answer without tools." : ".");
    }

    private static String shownName(ToolExecutionRequest request) {
        return request.name() == null ? "?" : request.name();
    }
}
