/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
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
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;

/** Posts a form to an OAuth 2.0 token endpoint. Used for client credentials and for the password check. */
final class TokenEndpoint {

    /**
     * The answer of a token endpoint.
     *
     * @param status the HTTP status
     * @param body the parsed JSON body, or {@code null} when the body was empty or not JSON
     */
    record Response(int status, JsonNode body) {

        /**
         * Returns a text field of the body.
         *
         * @param field the field name
         * @return the text, or {@code null} when absent
         */
        String text(String field) {
            if (body == null || !body.hasNonNull(field)) {
                return null;
            }
            return body.get(field).asText();
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenEndpoint() {}

    /**
     * Posts the form.
     *
     * @param http the HTTP client
     * @param url the token endpoint
     * @param form the form fields; {@code null} values are left out
     * @param timeout the request timeout
     * @return status and parsed body
     */
    static Response post(HttpClient http, String url, Map<String, String> form, Duration timeout) {
        String body = form.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("Interrupted while calling the token endpoint " + url, e);
        } catch (IOException e) {
            throw new IdentityProviderException("The token endpoint " + url + " could not be reached: " + e, e);
        }
        JsonNode json = null;
        byte[] bytes = response.body();
        if (bytes != null && bytes.length > 0) {
            try {
                json = MAPPER.readTree(bytes);
            } catch (JsonProcessingException e) {
                if (response.statusCode() == 200) {
                    throw new IdentityProviderException(
                            "The token endpoint " + url + " answered 200 with a body that is not JSON", e);
                }
            } catch (IOException e) {
                throw new IdentityProviderException("Reading the answer of the token endpoint " + url + " failed", e);
            }
        }
        return new Response(response.statusCode(), json);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
