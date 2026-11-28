/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.incidentnotifier;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Posts a JSON document with {@code java.net.http}. Thread-safe; a non-2xx answer is an {@link IOException}. */
final class HttpJsonPoster {

    private static final int MAX_ERROR_BODY = 200;

    private final HttpClient client;
    private final Duration timeout;

    HttpJsonPoster(Duration timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    void post(URI uri, Map<String, String> headers, String json) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while posting to " + describe(uri), e);
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            String body = response.body() == null ? "" : response.body();
            if (body.length() > MAX_ERROR_BODY) {
                body = body.substring(0, MAX_ERROR_BODY) + "...";
            }
            throw new IOException("POST to " + describe(uri) + " returned HTTP " + status + ": " + body);
        }
    }

    /** Scheme, host and port only: webhook URLs carry secrets in their path or query. */
    static String describe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }
}
