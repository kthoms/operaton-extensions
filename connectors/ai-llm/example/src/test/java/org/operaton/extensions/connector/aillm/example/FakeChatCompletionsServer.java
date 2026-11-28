/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A stand-in for an OpenAI-compatible chat completions API ({@code POST /v1/chat/completions}) so the
 * integration test runs offline and deterministically. The answer is computed from the request by a
 * function; every request body is recorded.
 */
final class FakeChatCompletionsServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Function<JsonNode, String> model;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    FakeChatCompletionsServer(Function<JsonNode, String> model) throws IOException {
        this.model = model;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(request);
            ObjectNode body = JSON.createObjectNode()
                    .put("id", "chatcmpl-fake")
                    .put("object", "chat.completion")
                    .put("created", 1_700_000_000L)
                    .put("model", request.path("model").asText());
            ObjectNode choice =
                    body.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
            choice.putObject("message").put("role", "assistant").put("content", model.apply(request));
            body.putObject("usage")
                    .put("prompt_tokens", 180)
                    .put("completion_tokens", 30)
                    .put("total_tokens", 210);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(out);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
