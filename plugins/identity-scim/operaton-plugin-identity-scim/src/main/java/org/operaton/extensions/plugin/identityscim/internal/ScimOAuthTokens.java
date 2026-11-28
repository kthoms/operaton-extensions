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
 * Ported: the OAuth 2.0 client credentials request (refreshOAuth2Token) and the early renewal of the token.
 * Changes: JDK HttpClient, one shared token under a lock instead of a mutable store object, the token
 * is renewed 30 seconds (at most half its lifetime) before it expires, errors name the OAuth error code.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fetches and caches an OAuth 2.0 access token (client credentials grant) for the SCIM requests. */
final class ScimOAuthTokens implements ScimAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(ScimOAuthTokens.class);
    private static final long DEFAULT_LIFETIME_SECONDS = 300;
    private static final long MAX_SKEW_SECONDS = 30;

    private final ScimSettings.Credentials credentials;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final LongSupplier nanoClock;

    private String token;
    private long expiresAtNanos;

    ScimOAuthTokens(
            ScimSettings.Credentials credentials, HttpClient http, Duration requestTimeout, LongSupplier nanoClock) {
        this.credentials = credentials;
        this.http = http;
        this.requestTimeout = requestTimeout;
        this.nanoClock = nanoClock;
    }

    @Override
    public synchronized String authorizationHeader() {
        if (token == null || nanoClock.getAsLong() - expiresAtNanos >= 0) {
            fetch();
        }
        return "Bearer " + token;
    }

    @Override
    public boolean refreshable() {
        return true;
    }

    @Override
    public synchronized void invalidate() {
        token = null;
    }

    private void fetch() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", credentials.clientId());
        form.put("client_secret", credentials.clientSecret());
        form.put("scope", credentials.scope());
        TokenEndpoint.Response response = TokenEndpoint.post(http, credentials.tokenUrl(), form, requestTimeout);
        if (response.status() != 200) {
            throw new IdentityProviderException("The OAuth2 token request to " + credentials.tokenUrl()
                    + " failed with status " + response.status() + describe(response));
        }
        String accessToken = response.text("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IdentityProviderException(
                    "The OAuth2 token endpoint " + credentials.tokenUrl() + " answered 200 without an access_token");
        }
        long lifetime = DEFAULT_LIFETIME_SECONDS;
        if (response.body().hasNonNull("expires_in")
                && response.body().get("expires_in").canConvertToLong()) {
            lifetime = Math.max(1, response.body().get("expires_in").asLong());
        }
        long skew = Math.min(MAX_SKEW_SECONDS, lifetime / 2);
        this.token = accessToken;
        this.expiresAtNanos =
                nanoClock.getAsLong() + Duration.ofSeconds(lifetime - skew).toNanos();
        LOG.debug("Fetched an OAuth2 access token for the SCIM client, valid for {} seconds", lifetime);
    }

    static String describe(TokenEndpoint.Response response) {
        String error = response.text("error");
        String description = response.text("error_description");
        if (error == null && description == null) {
            return "";
        }
        return " (" + (error == null ? "" : error) + (description == null ? "" : ": " + abbreviate(description)) + ")";
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
