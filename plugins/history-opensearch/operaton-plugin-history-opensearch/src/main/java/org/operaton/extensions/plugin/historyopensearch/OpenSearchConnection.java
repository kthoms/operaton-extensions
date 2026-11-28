/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * How to reach OpenSearch: endpoints, credentials, TLS and timeouts. Validated and immutable. Secrets
 * (password, API key) are never part of {@link #toString()}.
 *
 * <p>At most one authentication method may be configured: basic ({@code username} and {@code password})
 * or an API key (sent as {@code Authorization: ApiKey <apiKey>}, the Elasticsearch-compatible
 * base64 of {@code id:secret}).
 *
 * @param uris the endpoints, e.g. {@code https://search:9200}; requests go to them round-robin and move
 *     on to the next one when an endpoint fails
 * @param username user name for basic authentication; may be {@code null}
 * @param password password for basic authentication; may be {@code null}
 * @param apiKey API key; may be {@code null}
 * @param tls TLS settings
 * @param connectTimeout connection timeout
 * @param requestTimeout timeout of one HTTP request
 */
public record OpenSearchConnection(
        List<URI> uris,
        String username,
        String password,
        String apiKey,
        TlsSettings tls,
        Duration connectTimeout,
        Duration requestTimeout) {

    /** Validates and copies the settings. */
    public OpenSearchConnection {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("history-opensearch: at least one OpenSearch URI is required");
        }
        List<URI> checked = new ArrayList<>();
        for (URI uri : uris) {
            if (uri == null
                    || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("history-opensearch: invalid OpenSearch URI '" + uri
                        + "' (expected http://host:port or https://host:port)");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                        "history-opensearch: credentials must not be part of the URI; use username/password or apiKey");
            }
            checked.add(uri);
        }
        uris = List.copyOf(checked);
        boolean basic = username != null && !username.isBlank();
        boolean key = apiKey != null && !apiKey.isBlank();
        if (basic && key) {
            throw new IllegalArgumentException(
                    "history-opensearch: configure either username/password or apiKey, not both");
        }
        if (basic && (password == null)) {
            throw new IllegalArgumentException("history-opensearch: 'username' is set but 'password' is not");
        }
        if (!basic && password != null && !password.isBlank()) {
            throw new IllegalArgumentException("history-opensearch: 'password' is set but 'username' is not");
        }
        if (tls == null) {
            tls = TlsSettings.defaults();
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "history-opensearch: connectTimeout must be positive, was " + connectTimeout);
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "history-opensearch: requestTimeout must be positive, was " + requestTimeout);
        }
    }

    /** Whether basic authentication is configured. */
    public boolean hasBasicAuth() {
        return username != null && !username.isBlank();
    }

    /** Whether an API key is configured. */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "OpenSearchConnection[uris=" + uris + ", auth="
                + (hasBasicAuth() ? "basic(" + username + ")" : hasApiKey() ? "apiKey" : "none") + ", tls=" + tls
                + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout + "]";
    }
}
