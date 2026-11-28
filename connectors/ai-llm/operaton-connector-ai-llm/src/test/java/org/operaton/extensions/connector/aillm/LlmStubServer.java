/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the OpenAI chat completions API ({@code POST /v1/chat/completions}) and the
 * Anthropic messages API ({@code POST /v1/messages}). Answers come from a queue of canned responses;
 * every request is recorded. When the queue is empty it answers 500, so an unexpected extra call
 * fails the test.
 */
public final class LlmStubServer implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A recorded request. */
    public record Recorded(String path, Map<String, List<String>> headers, JsonNode body) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .map(e -> e.getValue().get(0))
                    .orElse(null);
        }
    }

    private record Canned(int status, String body) {}

    private final HttpServer server;
    private final Deque<Canned> responses = new ConcurrentLinkedDeque<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    public LlmStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the stub server", e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** Base URL for an {@code openai} model, e.g. {@code http://127.0.0.1:1234/v1}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public List<Recorded> requests() {
        return List.copyOf(requests);
    }

    public Recorded lastRequest() {
        return requests.get(requests.size() - 1);
    }

    public void reset() {
        responses.clear();
        requests.clear();
    }

    /** Queues an OpenAI answer with text content. */
    public LlmStubServer replyText(String content) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant").put("content", content);
        responses.add(new Canned(200, completion(message, "stop")));
        return this;
    }

    /** Queues an OpenAI answer that calls one tool. */
    public LlmStubServer replyToolCall(String id, String name, String argumentsJson) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant");
        message.putNull("content");
        ArrayNode calls = message.putArray("tool_calls");
        ObjectNode call = calls.addObject().put("id", id).put("type", "function");
        call.putObject("function").put("name", name).put("arguments", argumentsJson);
        responses.add(new Canned(200, completion(message, "tool_calls")));
        return this;
    }

    /** Queues an HTTP error. */
    public LlmStubServer replyError(int status, String body) {
        responses.add(new Canned(status, body));
        return this;
    }

    /** Queues an Anthropic messages API answer with text content. */
    public LlmStubServer replyAnthropicText(String text) {
        ObjectNode body = JSON.createObjectNode()
                .put("id", "msg_" + System.nanoTime())
                .put("type", "message")
                .put("role", "assistant")
                .put("model", "claude-stub")
                .put("stop_reason", "end_turn");
        body.putArray("content").addObject().put("type", "text").put("text", text);
        body.putObject("usage").put("input_tokens", 11).put("output_tokens", 5);
        responses.add(new Canned(200, body.toString()));
        return this;
    }

    private static String completion(ObjectNode message, String finishReason) {
        ObjectNode body = JSON.createObjectNode()
                .put("id", "chatcmpl-" + System.nanoTime())
                .put("object", "chat.completion")
                .put("created", 1_700_000_000L)
                .put("model", "stub-model");
        ObjectNode choice = body.putArray("choices").addObject().put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", finishReason);
        body.putObject("usage")
                .put("prompt_tokens", 20)
                .put("completion_tokens", 7)
                .put("total_tokens", 27);
        return body.toString();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? JSON.nullNode() : JSON.readTree(raw);
            requests.add(
                    new Recorded(exchange.getRequestURI().getPath(), Map.copyOf(exchange.getRequestHeaders()), body));
            Canned canned = responses.poll();
            if (canned == null) {
                canned = new Canned(500, "{\"error\":{\"message\":\"no canned response left in the stub\"}}");
            }
            byte[] out = canned.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(canned.status(), out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        }
    }

    /** Messages of a recorded OpenAI request as (role, content) pairs, for assertions. */
    public static List<String> roles(Recorded request) {
        List<String> roles = new ArrayList<>();
        request.body().get("messages").forEach(m -> roles.add(m.get("role").asText()));
        return roles;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
