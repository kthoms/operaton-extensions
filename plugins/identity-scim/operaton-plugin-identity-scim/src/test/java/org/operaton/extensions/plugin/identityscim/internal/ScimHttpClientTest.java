/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

class ScimHttpClientTest {

    private FakeScimServer server;
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach
    void startServer() {
        server = Scim.seededServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private ScimHttpClient client(ScimIdentityProviderPlugin plugin) {
        return ScimHttpClient.create(plugin.validate(), clock::get);
    }

    @Test
    void sendsBearerTokenAcceptHeaderAndCustomHeaders() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setCustomHeaders(Map.of("X-Tenant", "acme"));

        JsonNode response = client(plugin).search("/Users", "userName eq \"alice\"", null, true, 1, 10);

        assertThat(response.get("totalResults").asInt()).isEqualTo(1);
        FakeScimServer.Recorded request = server.requestsTo("/Users").get(0);
        assertThat(request.headers().get("authorization")).isEqualTo("Bearer t0ken");
        assertThat(request.headers().get("accept")).contains("application/scim+json");
        assertThat(request.headers().get("x-tenant")).isEqualTo("acme");
    }

    @Test
    void sendsBasicCredentials() {
        server.requireAuthorization("Basic c3ZjOnMzY3JldA==");
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl(server.baseUrl());
        plugin.setAuthenticationType("basic");
        plugin.setUsername("svc");
        plugin.setPassword("s3cret");

        assertThat(client(plugin).resource("/Users", "u-1").get("userName").asText())
                .isEqualTo("alice");
    }

    @Test
    void sendsNoAuthorizationWhenNone() {
        server.requireAuthorization(null);
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl(server.baseUrl());
        plugin.setAuthenticationType("none");

        client(plugin).resource("/Users", "u-1");

        assertThat(server.requestsTo("/Users").get(0).headers()).doesNotContainKey("authorization");
    }

    @Test
    void aRejectedTokenFailsWithTheStatusAndTheServersDetail() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setBearerToken("wrong");

        assertThatThrownBy(() -> client(plugin).resource("/Users", "u-1"))
                .isInstanceOfSatisfying(
                        ScimHttpException.class, e -> assertThat(e.getStatus()).isEqualTo(401))
                .hasMessageContaining("status 401")
                .hasMessageContaining("Unauthorized")
                .hasMessageContaining("/Users/u-1")
                .hasMessageNotContaining("wrong");
        assertThat(server.requestsTo("/Users"))
                .as("a static token is not retried")
                .hasSize(1);
    }

    @Test
    void aMissingResourceIsReportedWithStatus404() {
        assertThatThrownBy(() -> client(Scim.plugin(server)).resource("/Users", "nope"))
                .isInstanceOfSatisfying(
                        ScimHttpException.class, e -> assertThat(e.getStatus()).isEqualTo(404))
                .hasMessageContaining("Resource nope not found");
    }

    @Test
    void serverErrorsAreNotSwallowed() {
        server.failWith(503, "{\"detail\":\"maintenance window\"}");

        assertThatThrownBy(() -> client(Scim.plugin(server)).search("/Users", null, null, true, 1, 10))
                .isInstanceOf(ScimHttpException.class)
                .hasMessageContaining("status 503")
                .hasMessageContaining("maintenance window");
    }

    @Test
    void anErrorBodyThatIsNotJsonIsQuotedAbbreviated() {
        server.failWith(502, "<html>bad gateway " + "x".repeat(500) + "</html>");

        assertThatThrownBy(() -> client(Scim.plugin(server)).search("/Users", null, null, true, 1, 10))
                .hasMessageContaining("status 502")
                .hasMessageContaining("bad gateway")
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(600));
    }

    @Test
    void aBodyThatIsNotAJsonObjectFails() {
        server.failWith(200, "this is not json");
        assertThatThrownBy(() -> client(Scim.plugin(server)).resource("/Users", "u-1"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("not JSON");

        server.failWith(200, "[1,2]");
        assertThatThrownBy(() -> client(Scim.plugin(server)).resource("/Users", "u-1"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("without a JSON object");

        server.failWith(200, "");
        assertThatThrownBy(() -> client(Scim.plugin(server)).resource("/Users", "u-1"))
                .isInstanceOf(IdentityProviderException.class);
    }

    @Test
    void anUnreachableServerFailsWithTheUrl() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        ScimHttpClient client = client(plugin);
        server.close();

        assertThatThrownBy(() -> client.resource("/Users", "u-1"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("/Users/u-1")
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void aSlowServerFailsAfterTheRequestTimeout() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setRequestTimeoutMillis(300);
        CountDownLatch never = new CountDownLatch(1);
        server.holdAnswers(never);
        try {
            assertThatThrownBy(() -> client(plugin).resource("/Users", "u-1"))
                    .isInstanceOf(IdentityProviderException.class)
                    .hasMessageContaining("timed out");
        } finally {
            never.countDown();
        }
    }

    @Test
    void encodesFiltersWithSpacesQuotesAndNonAsciiCharacters() {
        String filter = "name.givenName eq \"Jürgen \\\"JJ\\\"\" and active eq true";

        client(Scim.plugin(server)).search("/Users", filter, "userName", false, 3, 7);

        FakeScimServer.Recorded request = server.requestsTo("/Users").get(0);
        assertThat(request.param("filter")).isEqualTo(filter);
        assertThat(request.param("startIndex")).isEqualTo("3");
        assertThat(request.param("count")).isEqualTo("7");
        assertThat(request.param("sortBy")).isEqualTo("userName");
        assertThat(request.param("sortOrder")).isEqualTo("descending");
        assertThat(request.rawQuery()).doesNotContain("+").doesNotContain(" ").contains("%20");
    }

    @Test
    void cachesResponsesUntilTheTtlElapsed() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setCacheTtlMillis(60_000);
        ScimHttpClient client = client(plugin);

        client.search("/Users", "userName eq \"alice\"", null, true, 1, 10);
        client.search("/Users", "userName eq \"alice\"", null, true, 1, 10);
        client.search("/Users", "userName eq \"bob\"", null, true, 1, 10);
        assertThat(server.requestsTo("/Users"))
                .as("same URL once, other URL once more")
                .hasSize(2);

        clock.addAndGet(Duration.ofSeconds(61).toNanos());
        client.search("/Users", "userName eq \"alice\"", null, true, 1, 10);
        assertThat(server.requestsTo("/Users")).hasSize(3);
    }

    @Test
    void failuresAreNotCached() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        ScimHttpClient client = client(plugin);
        server.failWith(500, "{}");
        assertThatThrownBy(() -> client.resource("/Users", "u-1")).isInstanceOf(ScimHttpException.class);
        server.stopFailing();

        assertThat(client.resource("/Users", "u-1").get("userName").asText()).isEqualTo("alice");
    }

    @Test
    void aZeroTtlDisablesTheCache() {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setCacheTtlMillis(0);
        ScimHttpClient client = client(plugin);

        client.resource("/Users", "u-1");
        client.resource("/Users", "u-1");

        assertThat(server.requestsTo("/Users/u-1")).hasSize(2);
    }

    @Test
    void clearCacheForcesANewRequest() {
        ScimHttpClient client = client(Scim.plugin(server));
        client.resource("/Users", "u-1");
        client.clearCache();
        client.resource("/Users", "u-1");

        assertThat(server.requestsTo("/Users/u-1")).hasSize(2);
    }

    // ---- OAuth2 client credentials

    private ScimIdentityProviderPlugin oauthPlugin() {
        server.requireOAuthToken("operaton", "secret", 3600);
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl(server.baseUrl());
        plugin.setAuthenticationType("oauth2");
        plugin.setOauth2TokenUrl(server.tokenUrl());
        plugin.setOauth2ClientId("operaton");
        plugin.setOauth2ClientSecret("secret");
        plugin.setOauth2Scope("scim.read");
        plugin.setCacheTtlMillis(0);
        return plugin;
    }

    @Test
    void fetchesATokenOnFirstUseAndReusesItWhileValid() {
        ScimHttpClient client = client(oauthPlugin());
        assertThat(server.tokensIssued()).as("lazy").isZero();

        client.resource("/Users", "u-1");
        client.resource("/Users", "u-2");

        assertThat(server.tokensIssued()).isEqualTo(1);
        assertThat(server.requestsTo("/Users").get(0).headers().get("authorization"))
                .startsWith("Bearer ");
        FakeScimServer.Recorded tokenRequest = server.requests().stream()
                .filter(r -> r.path().equals("/oauth/token"))
                .findFirst()
                .orElseThrow();
        assertThat(tokenRequest.method()).isEqualTo("POST");
    }

    @Test
    void renewsTheTokenBeforeItExpires() {
        ScimHttpClient client = client(oauthPlugin());
        client.resource("/Users", "u-1");

        clock.addAndGet(Duration.ofSeconds(3500).toNanos());
        client.resource("/Users", "u-1");
        assertThat(server.tokensIssued()).as("3500 of 3600 seconds, 30 s skew").isEqualTo(1);

        clock.addAndGet(Duration.ofSeconds(80).toNanos());
        client.resource("/Users", "u-1");
        assertThat(server.tokensIssued()).isEqualTo(2);
    }

    @Test
    void renewsTheTokenOnceWhenTheServerRejectsIt() {
        ScimHttpClient client = client(oauthPlugin());
        client.resource("/Users", "u-1");
        server.revokeTokens();

        assertThat(client.resource("/Users", "u-1").get("userName").asText()).isEqualTo("alice");

        assertThat(server.tokensIssued()).isEqualTo(2);
    }

    @Test
    void aWrongClientSecretFailsWithTheOAuthErrorAndNeverLeaksIt() {
        ScimIdentityProviderPlugin plugin = oauthPlugin();
        plugin.setOauth2ClientSecret("wrong-secret");

        assertThatThrownBy(() -> client(plugin).resource("/Users", "u-1"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 401")
                .hasMessageContaining("invalid_client")
                .hasMessageNotContaining("wrong-secret");
    }

    @Test
    void aRejectedTokenRequestFailsTheSearch() {
        ScimIdentityProviderPlugin plugin = oauthPlugin();
        plugin.setOauth2TokenUrl(server.baseUrl() + "/Users/u-1");

        assertThatThrownBy(() -> client(plugin).resource("/Groups", "g-1"))
                .isInstanceOf(IdentityProviderException.class);
    }
}
