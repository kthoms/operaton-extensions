/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.operaton.extensions.plugin.historyopensearch.OpenSearchConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal client for the REST API of OpenSearch (and API-compatible Elasticsearch), built on
 * {@code java.net.http}: the plugin needs three calls (templates, bulk, search) and must not add
 * a second HTTP stack or a second Jackson to the engine's class path. Requests rotate over the
 * configured endpoints; an endpoint that fails is skipped by the next attempt. Thread-safe.
 */
public final class OpenSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenSearchClient.class);
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);
    private static final int ERROR_BODY_LIMIT = 500;

    private final List<URI> uris;
    private final ObjectMapper json;
    private final HttpClient http;
    private final java.time.Duration requestTimeout;
    private final String authorization;
    private final AtomicInteger next = new AtomicInteger();

    /** Creates the client; builds the TLS context when TLS settings are present. */
    public OpenSearchClient(OpenSearchConnection connection, ObjectMapper json) {
        this.uris = connection.uris();
        this.json = json;
        this.requestTimeout = connection.requestTimeout();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connection.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        boolean tls = uris.stream().anyMatch(uri -> "https".equalsIgnoreCase(uri.getScheme()));
        if (tls) {
            builder.sslContext(TlsContexts.create(connection.tls()));
            if (connection.tls().insecureSkipVerify()) {
                LOG.warn(
                        "history-opensearch: TLS verification is DISABLED (insecureSkipVerify); do not use this in production");
            }
        }
        this.http = builder.build();
        if (connection.hasBasicAuth()) {
            this.authorization = "Basic "
                    + Base64.getEncoder()
                            .encodeToString((connection.username() + ":" + connection.password())
                                    .getBytes(StandardCharsets.UTF_8));
        } else if (connection.hasApiKey()) {
            this.authorization = "ApiKey " + connection.apiKey();
        } else {
            this.authorization = null;
        }
    }

    /**
     * Sends one request and returns the parsed JSON response.
     *
     * @param method HTTP method
     * @param path path and query, starting with {@code /}
     * @param body request body, or {@code null}
     * @param contentType content type of the body
     * @throws RestException when the request fails or the status is not 2xx
     */
    public JsonNode execute(String method, String path, String body, String contentType) {
        URI base = uris.get(Math.floorMod(next.getAndIncrement(), uris.size()));
        URI target = URI.create(base.toString().replaceAll("/+$", "") + path);
        HttpRequest.Builder request =
                HttpRequest.newBuilder(target).timeout(requestTimeout).header("Accept", "application/json");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", contentType);
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RestException(method + " " + describe(base, path) + " failed: " + e, -1, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestException(method + " " + describe(base, path) + " interrupted", -1, false, e);
        }
        int status = response.statusCode();
        if (status / 100 != 2) {
            String text = response.body() == null ? "" : response.body();
            if (text.length() > ERROR_BODY_LIMIT) {
                text = text.substring(0, ERROR_BODY_LIMIT) + "...";
            }
            throw new RestException(
                    method + " " + describe(base, path) + " returned HTTP " + status + ": " + text,
                    status,
                    RETRYABLE_STATUS.contains(status),
                    null);
        }
        String text = response.body();
        if (text == null || text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new RestException(
                    method + " " + describe(base, path) + " returned unreadable JSON", status, false, e);
        }
    }

    private static String describe(URI base, String path) {
        int query = path.indexOf('?');
        return base.getScheme() + "://" + base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "")
                + (query < 0 ? path : path.substring(0, query));
    }
}
