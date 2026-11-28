/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/**
 * A tiny HTTP server that plays OpenSearch for <em>fault injection only</em>: it answers the plugin's calls
 * with scripted errors (HTTP 503, rejected bulk items, permanent mapping errors) that a healthy real cluster
 * does not produce on demand, and records the requests (headers, bodies). What the plugin writes to a real
 * cluster is verified in the Testcontainers tests.
 */
final class StubOpenSearch implements AutoCloseable {

    record Request(String method, String path, Map<String, String> headers, String body) {}

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Function<Request, Response>> bulkScript = new ConcurrentLinkedQueue<>();

    record Response(int status, String body) {}

    private final boolean tls;

    StubOpenSearch() throws IOException {
        this(null, false);
    }

    /** An HTTPS stub with the given server certificate; optionally it demands a client certificate. */
    StubOpenSearch(SSLContext ssl, boolean requireClientCertificate) throws IOException {
        this.tls = ssl != null;
        if (ssl == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } else {
            HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl) {
                @Override
                public void configure(HttpsParameters params) {
                    javax.net.ssl.SSLParameters parameters = ssl.getDefaultSSLParameters();
                    parameters.setNeedClientAuth(requireClientCertificate);
                    params.setSSLParameters(parameters);
                }
            });
            server = https;
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return (tls ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        return new ArrayList<>(requests);
    }

    List<Request> bulkRequests() {
        return requests().stream().filter(r -> r.path().startsWith("/_bulk")).toList();
    }

    /** The next bulk request is answered by the function; later ones fall back to "everything accepted". */
    void onNextBulk(Function<Request, Response> response) {
        bulkScript.add(response);
    }

    /** A bulk response that accepts every operation of the request. */
    static Response accepted(Request request) {
        return new Response(200, "{\"errors\":false}");
    }

    static Response status(int status) {
        return new Response(status, "{\"error\":\"scripted\"}");
    }

    /** A bulk response that gives the operation at the position (0-based) the status and error, and accepts the others. */
    static Function<Request, Response> itemFails(int position, int status, String errorType) {
        return request -> {
            int operations = request.body().split("\n").length / 2;
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < operations; i++) {
                if (i > 0) {
                    items.append(',');
                }
                if (i == position) {
                    items.append("{\"update\":{\"status\":" + status + ",\"error\":{\"type\":\"" + errorType
                            + "\",\"reason\":\"scripted failure\"}}}");
                } else {
                    items.append("{\"update\":{\"status\":200}}");
                }
            }
            return new Response(200, "{\"errors\":true,\"items\":[" + items + "]}");
        };
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        Request request = new Request(
                exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, body);
        requests.add(request);
        Response response;
        if (request.path().startsWith("/_bulk")) {
            Function<Request, Response> scripted = bulkScript.poll();
            response = scripted == null ? accepted(request) : scripted.apply(request);
        } else if (request.path().startsWith("/_index_template/")) {
            response = new Response(200, "{\"acknowledged\":true}");
        } else {
            response = new Response(200, "{}");
        }
        byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
