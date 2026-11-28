/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.identity.Group;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.extensions.plugin.identityscim.support.FakeScimServer;
import org.operaton.extensions.plugin.identityscim.support.Scim;

/**
 * An engine whose SCIM access uses OAuth 2.0 client credentials, whose groups come from the users'
 * {@code groups} claim and whose passwords are checked at the token endpoint.
 */
class ScimOAuthEngineTest {

    static final FakeScimServer SCIM = Scim.seededServer()
            .withUserGroupsClaim()
            .memberFilterSupported(false)
            .password("alice", "correct horse")
            .requireOAuthToken("operaton", "client-secret", 3600);

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder()
            .randomEngineName()
            .closeEngineAfterAllTests()
            .configurator(cfg -> {
                ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
                plugin.setBaseUrl(SCIM.baseUrl());
                plugin.setAuthenticationType("oauth2");
                plugin.setOauth2TokenUrl(SCIM.tokenUrl());
                plugin.setOauth2ClientId("operaton");
                plugin.setOauth2ClientSecret("client-secret");
                plugin.setMembershipSource("user-groups");
                plugin.setPasswordCheck("oauth-password");
                plugin.setPasswordTokenUrl(SCIM.tokenUrl());
                plugin.setPasswordClientId("operaton-webapp");
                cfg.getProcessEnginePlugins().add(plugin);
            })
            .build();

    @AfterAll
    static void stopServer() {
        SCIM.close();
    }

    @Test
    void readsTheDirectoryWithAnOAuthTokenAndGroupsFromTheUserClaim() {
        assertThat(engine.getIdentityService().createUserQuery().count()).isEqualTo(4);
        assertThat(engine.getIdentityService()
                        .createGroupQuery()
                        .groupMember("carol")
                        .orderByGroupId()
                        .asc()
                        .list())
                .extracting(Group::getId)
                .containsExactly("all-staff", "approvers");
        assertThat(SCIM.tokensIssued()).as("one token serves all requests").isEqualTo(1);
    }

    @Test
    void checksPasswordsAtTheTokenEndpoint() {
        assertThat(engine.getIdentityService().checkPassword("alice", "correct horse"))
                .isTrue();
        assertThat(engine.getIdentityService().checkPassword("alice", "battery staple"))
                .isFalse();
        assertThat(engine.getIdentityService().checkPassword("nobody", "correct horse"))
                .isFalse();
    }
}
