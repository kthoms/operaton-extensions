/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimClient.java (Apache-2.0)
 * Ported: the search and get-by-id requests (URL, filter, startIndex, count, sort parameters), the
 * bearer, basic and OAuth2 authentication headers, the retry after a 401 with an OAuth2 token, the
 * response cache keyed by URL. Changes: the JDK HttpClient instead of Apache HttpClient 5 (no shaded
 * dependencies), one client per plugin instead of one per session, an explicit failure for every non-200
 * answer and every malformed body, no write operations, no trust-all TLS option.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends read-only requests to the SCIM server and parses the JSON answers. Successful answers are
 * cached for the configured time to live. Every unexpected answer (a status other than 200, a body
 * that is not a JSON object, a transport failure) is thrown as an {@link IdentityProviderException}.
 *
 * <p>Thread-safe: the JDK {@link HttpClient}, the cache and the authenticator are.
 */
public final class ScimHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(ScimHttpClient.class);

    private final ScimSettings settings;
    private final HttpClient http;
    private final ScimAuthenticator authenticator;
    private final ScimTtlCache<JsonNode> cache;
    private final ObjectMapper mapper = new ObjectMapper();

    ScimHttpClient(ScimSettings settings, HttpClient http, ScimAuthenticator authenticator, LongSupplier nanoClock) {
        this.settings = settings;
        this.http = http;
        this.authenticator = authenticator;
        this.cache = new ScimTtlCache<>(settings.cacheTtl(), settings.cacheMaxEntries(), nanoClock);
    }

    /**
     * Creates the client for the settings.
     *
     * @param settings the validated settings
     * @return the client
     */
    public static ScimHttpClient create(ScimSettings settings) {
        return create(settings, System::nanoTime);
    }

    static ScimHttpClient create(ScimSettings settings, LongSupplier clock) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        ScimSettings.Credentials credentials = settings.credentials();
        ScimAuthenticator authenticator = switch (credentials.type()) {
            case NONE -> staticHeader(null);
            case BEARER -> staticHeader("Bearer " + credentials.bearerToken());
            case BASIC ->
                staticHeader("Basic "
                        + Base64.getEncoder()
                                .encodeToString((credentials.username() + ":" + credentials.password())
                                        .getBytes(StandardCharsets.UTF_8)));
            case OAUTH2 -> new ScimOAuthTokens(credentials, http, settings.requestTimeout(), clock);
        };
        return new ScimHttpClient(settings, http, authenticator, clock);
    }

    private static ScimAuthenticator staticHeader(String value) {
        return new ScimAuthenticator() {
            @Override
            public String authorizationHeader() {
                return value;
            }

            @Override
            public boolean refreshable() {
                return false;
            }

            @Override
            public void invalidate() {
                // a static credential cannot be renewed
            }
        };
    }

    /**
     * Searches a resource collection: {@code GET <endpoint>?filter=..&startIndex=..&count=..}.
     *
     * @param endpoint the collection path, for example {@code /Users}
     * @param filter the SCIM filter, or {@code null}
     * @param sortBy the attribute to sort by, or {@code null}
     * @param ascending the sort direction, used with {@code sortBy}
     * @param startIndex the 1-based index of the first resource
     * @param count the maximum number of resources
     * @return the SCIM ListResponse
     */
    public JsonNode search(
            String endpoint, String filter, String sortBy, boolean ascending, int startIndex, int count) {
        Map<String, String> params = new LinkedHashMap<>();
        if (filter != null && !filter.isBlank()) {
            params.put("filter", filter);
        }
        if (sortBy != null) {
            params.put("sortBy", sortBy);
            params.put("sortOrder", ascending ? "ascending" : "descending");
        }
        params.put("startIndex", Integer.toString(startIndex));
        params.put("count", Integer.toString(count));
        StringBuilder query = new StringBuilder();
        params.forEach((k, v) -> query.append(query.length() == 0 ? '?' : '&')
                .append(k)
                .append('=')
                .append(encode(v)));
        return get(URI.create(settings.baseUrl() + endpoint + query));
    }

    /**
     * Reads one resource: {@code GET <endpoint>/<id>}.
     *
     * @param endpoint the collection path
     * @param id the SCIM id of the resource
     * @return the resource
     * @throws ScimHttpException with status 404 when the resource does not exist
     */
    public JsonNode resource(String endpoint, String id) {
        return get(URI.create(settings.baseUrl() + endpoint + "/" + encode(id)));
    }

    /** Drops the cached responses and the cached access token. */
    public void clearCache() {
        cache.clear();
        authenticator.invalidate();
    }

    /**
     * The HTTP client for calls outside the SCIM service (the password check).
     *
     * @return the shared client
     */
    HttpClient httpClient() {
        return http;
    }

    private JsonNode get(URI uri) {
        String key = uri.toString();
        JsonNode cached = cache.get(key);
        if (cached != null) {
            LOG.debug("SCIM GET {} answered from the cache", uri);
            return cached;
        }
        JsonNode body = exchange(uri, true);
        cache.put(key, body);
        return body;
    }

    private JsonNode exchange(URI uri, boolean mayRenewToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(settings.requestTimeout())
                .header("Accept", "application/scim+json, application/json")
                .GET();
        settings.customHeaders().forEach(request::header);
        String authorization = authenticator.authorizationHeader();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("Interrupted while calling SCIM GET " + uri, e);
        } catch (IOException e) {
            throw new IdentityProviderException("The SCIM request GET " + uri + " failed: " + e, e);
        }
        int status = response.statusCode();
        LOG.debug("SCIM GET {} -> {}", uri, status);
        if (status == 401 && mayRenewToken && authenticator.refreshable()) {
            authenticator.invalidate();
            return exchange(uri, false);
        }
        if (status != 200) {
            throw new ScimHttpException(
                    status, "The SCIM request GET " + uri + " failed with status " + status + errorDetail(response));
        }
        try {
            JsonNode body = mapper.readTree(response.body());
            if (body == null || !body.isObject()) {
                throw new IdentityProviderException(
                        "The SCIM request GET " + uri + " answered 200 without a JSON object in the body");
            }
            return body;
        } catch (JsonProcessingException e) {
            throw new IdentityProviderException(
                    "The SCIM request GET " + uri + " answered 200 with a body that is not JSON", e);
        } catch (IOException e) {
            throw new IdentityProviderException("Reading the answer of the SCIM request GET " + uri + " failed", e);
        }
    }

    private String errorDetail(HttpResponse<byte[]> response) {
        byte[] bytes = response.body();
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String detail = null;
        try {
            JsonNode body = mapper.readTree(bytes);
            if (body != null && body.hasNonNull("detail")) {
                detail = body.get("detail").asText();
            } else if (body != null && body.hasNonNull("error_description")) {
                detail = body.get("error_description").asText();
            }
        } catch (IOException e) {
            LOG.debug("The error body of the SCIM answer is not JSON: {}", e.toString());
        }
        if (detail == null) {
            detail = new String(bytes, StandardCharsets.UTF_8);
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        return detail.isEmpty() ? "" : ": " + (detail.length() <= 300 ? detail : detail.substring(0, 300) + "...");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
