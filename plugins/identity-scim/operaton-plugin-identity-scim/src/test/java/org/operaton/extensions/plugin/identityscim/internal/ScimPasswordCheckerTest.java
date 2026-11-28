/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;
import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;
import org.slf4j.LoggerFactory;

class ScimPasswordCheckerTest {

    private FakeScimServer server;

    @BeforeEach
    void startServer() {
        server = Scim.seededServer();
        server.password("alice", "correct horse").password("bob", "hunter2");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private ScimPasswordChecker checker(Consumer<ScimIdentityProviderPlugin> configure) {
        ScimIdentityProviderPlugin plugin = Scim.plugin(server);
        plugin.setCacheTtlMillis(0);
        configure.accept(plugin);
        var settings = plugin.validate();
        ScimHttpClient client = ScimHttpClient.create(settings);
        return ScimPasswordChecker.create(settings, new ScimDirectory(settings, client), client);
    }

    private ScimPasswordChecker oauthChecker() {
        return checker(p -> {
            p.setPasswordCheck("oauth-password");
            p.setPasswordTokenUrl(server.tokenUrl());
            p.setPasswordClientId("webapp");
            p.setPasswordClientSecret("webapp-secret");
            p.setPasswordScope("openid");
        });
    }

    @Test
    void isNotSupportedByDefaultAndSaysWhatToDo() {
        ScimPasswordChecker checker = checker(p -> {});

        assertThatThrownBy(() -> checker.check("alice", "correct horse"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("not supported")
                .hasMessageContaining("passwordCheck=oauth-password")
                .hasMessageNotContaining("correct horse");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void acceptsAPasswordTheTokenEndpointAccepts() {
        assertThat(oauthChecker().check("alice", "correct horse")).isTrue();

        assertThat(server.lastTokenForm())
                .containsEntry("grant_type", "password")
                .containsEntry("username", "alice")
                .containsEntry("password", "correct horse")
                .containsEntry("client_id", "webapp")
                .containsEntry("client_secret", "webapp-secret")
                .containsEntry("scope", "openid");
    }

    @Test
    void rejectsAWrongPassword() {
        assertThat(oauthChecker().check("alice", "wrong")).isFalse();
        assertThat(oauthChecker().check("alice", "hunter2"))
                .as("the password of another user")
                .isFalse();
    }

    @Test
    void rejectsAnUnknownUserWithoutCallingTheTokenEndpoint() {
        server.password("mallory", "pw");

        assertThat(oauthChecker().check("mallory", "pw")).isFalse();
        assertThat(server.requests().stream().filter(r -> r.path().equals("/oauth/token")))
                .isEmpty();
    }

    @Test
    void rejectsEmptyCredentialsWithoutAnyRequest() {
        ScimPasswordChecker checker = oauthChecker();

        assertThat(checker.check("alice", "")).isFalse();
        assertThat(checker.check("alice", null)).isFalse();
        assertThat(checker.check("", "x")).isFalse();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void anErrorOtherThanInvalidGrantIsAFailureNotARejection() {
        server.failTokenEndpoint(401, "{\"error\":\"invalid_client\",\"error_description\":\"Invalid client secret\"}");

        assertThatThrownBy(() -> oauthChecker().check("alice", "correct horse"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 401")
                .hasMessageContaining("invalid_client")
                .hasMessageNotContaining("correct horse");
    }

    @Test
    void aServerErrorIsAFailureNotARejection() {
        server.failTokenEndpoint(500, "oops");

        assertThatThrownBy(() -> oauthChecker().check("alice", "correct horse"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("status 500");
    }

    @Test
    void anUnreachableTokenEndpointFails() {
        ScimPasswordChecker checker = checker(p -> {
            p.setPasswordCheck("oauth-password");
            p.setPasswordTokenUrl("http://127.0.0.1:1/token");
            p.setPasswordClientId("webapp");
        });

        assertThatThrownBy(() -> checker.check("alice", "correct horse"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("could not be reached");
    }

    @Test
    void neitherPasswordsNorTokensAreLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger("org.operaton.extensions");
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
        try {
            ScimPasswordChecker checker = oauthChecker();
            checker.check("alice", "correct horse");
            checker.check("alice", "wrong-password");
            List<String> logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(logged).isNotEmpty();
            assertThat(String.join("\n", logged))
                    .doesNotContain("correct horse")
                    .doesNotContain("wrong-password")
                    .doesNotContain("webapp-secret")
                    .doesNotContain(Scim.TOKEN);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
