/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimPasswordCheck;
import org.operaton.extensions.plugin.identityscim.ScimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers {@code IdentityService.checkPassword}. SCIM has no credential check, so the answer is
 * either "not supported" (an exception) or a delegation to an OAuth 2.0 token endpoint. Passwords
 * are never logged, cached or stored.
 */
public interface ScimPasswordChecker {

    /**
     * Checks a user's password.
     *
     * @param userId the engine user id
     * @param password the password
     * @return {@code true} when the credentials are valid, {@code false} when they are rejected
     * @throws IdentityProviderException when the check is not supported or could not be performed
     */
    boolean check(String userId, String password);

    /**
     * Creates the checker for the settings.
     *
     * @param settings the validated settings
     * @param directory the directory, to verify that the user exists
     * @param client the SCIM client, whose HTTP client is reused
     * @return the checker
     */
    static ScimPasswordChecker create(ScimSettings settings, ScimDirectory directory, ScimHttpClient client) {
        ScimSettings.PasswordCheck config = settings.passwordCheck();
        if (config.mode() == ScimPasswordCheck.OAUTH_PASSWORD) {
            return new OAuthPasswordChecker(config, directory, client.httpClient(), settings.requestTimeout());
        }
        return (userId, password) -> {
            throw new IdentityProviderException(
                    "Checking passwords is not supported by the SCIM identity provider: SCIM defines no"
                            + " credential check. Authenticate users with SSO/OIDC, or set passwordCheck=oauth-password to verify"
                            + " passwords at an OAuth 2.0 token endpoint.");
        };
    }

    /** Verifies a password with the OAuth 2.0 resource owner password credentials grant. */
    final class OAuthPasswordChecker implements ScimPasswordChecker {

        private static final Logger LOG = LoggerFactory.getLogger(OAuthPasswordChecker.class);

        private final ScimSettings.PasswordCheck config;
        private final ScimDirectory directory;
        private final HttpClient http;
        private final Duration timeout;

        OAuthPasswordChecker(
                ScimSettings.PasswordCheck config, ScimDirectory directory, HttpClient http, Duration timeout) {
            this.config = config;
            this.directory = directory;
            this.http = http;
            this.timeout = timeout;
        }

        @Override
        public boolean check(String userId, String password) {
            if (userId == null || userId.isEmpty() || password == null || password.isEmpty()) {
                return false;
            }
            if (directory.findUser(userId) == null) {
                LOG.debug("Password check for {}: no such user in the SCIM directory", userId);
                return false;
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "password");
            form.put("client_id", config.clientId());
            form.put("client_secret", config.clientSecret());
            form.put("scope", config.scope());
            form.put("username", userId);
            form.put("password", password);
            TokenEndpoint.Response response = TokenEndpoint.post(http, config.tokenUrl(), form, timeout);
            if (response.status() == 200) {
                if (response.text("access_token") == null) {
                    throw new IdentityProviderException(
                            "The token endpoint " + config.tokenUrl() + " answered 200 without an access_token");
                }
                return true;
            }
            if ((response.status() == 400 || response.status() == 401)
                    && "invalid_grant".equals(response.text("error"))) {
                LOG.debug("Password check for {}: the token endpoint rejected the credentials", userId);
                return false;
            }
            throw new IdentityProviderException("The password check at " + config.tokenUrl() + " failed with status "
                    + response.status() + ScimOAuthTokens.describe(response));
        }
    }
}
