/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.identityscim.ScimAuthenticationType;
import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;
import org.operaton.extensions.plugin.identityscim.ScimMembershipSource;
import org.operaton.extensions.plugin.identityscim.ScimPasswordCheck;
import org.operaton.extensions.plugin.identityscim.ScimSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ScimIdentityProviderAutoConfigurationTest {

    private static final String PREFIX = "operaton.extensions.plugin.identity-scim.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ScimIdentityProviderAutoConfiguration.class));

    @Test
    void registersThePluginWithTheDefaults() {
        runner.withPropertyValues(PREFIX + "base-url=https://scim.example.com/v2", PREFIX + "bearer-token=t")
                .run(context -> {
                    assertThat(context).hasSingleBean(ScimIdentityProviderPlugin.class);
                    ScimSettings settings =
                            context.getBean(ScimIdentityProviderPlugin.class).validate();
                    assertThat(settings.baseUrl()).isEqualTo("https://scim.example.com/v2");
                    assertThat(settings.credentials().type()).isEqualTo(ScimAuthenticationType.BEARER);
                    assertThat(settings.mapping().userId()).isEqualTo("userName");
                    assertThat(settings.mapping().groupId()).isEqualTo("displayName");
                    assertThat(settings.cacheTtl()).isEqualTo(Duration.ofMinutes(1));
                    assertThat(settings.passwordCheck().mode()).isEqualTo(ScimPasswordCheck.UNSUPPORTED);
                });
    }

    @Test
    void mapsEveryProperty() {
        runner.withPropertyValues(
                        PREFIX + "base-url=https://scim.example.com/v2/",
                        PREFIX + "users-endpoint=/scim/Users",
                        PREFIX + "groups-endpoint=/scim/Groups",
                        PREFIX + "authentication-type=oauth2",
                        PREFIX + "oauth2.token-url=https://login.example.com/token",
                        PREFIX + "oauth2.client-id=operaton",
                        PREFIX + "oauth2.client-secret=s3cret",
                        PREFIX + "oauth2.scope=scim.read",
                        PREFIX + "user.id-attribute=id",
                        PREFIX + "user.first-name-attribute=givenName",
                        PREFIX + "user.last-name-attribute=familyName",
                        PREFIX + "user.email-attribute=emails[type eq \"work\"].value",
                        PREFIX + "user.groups-attribute=memberOf",
                        PREFIX + "user.base-filter=active eq true",
                        PREFIX + "group.id-attribute=externalId",
                        PREFIX + "group.name-attribute=displayName",
                        PREFIX + "group.members-attribute=users",
                        PREFIX + "group.base-filter=displayName sw \"op-\"",
                        PREFIX + "membership-source=user-groups",
                        PREFIX + "page-size=25",
                        PREFIX + "connect-timeout=2s",
                        PREFIX + "request-timeout=5s",
                        PREFIX + "cache.ttl=30s",
                        PREFIX + "cache.max-entries=10",
                        PREFIX + "authorization-check-enabled=false",
                        PREFIX + "custom-headers.X-Api-Version=2",
                        PREFIX + "password-check.mode=oauth-password",
                        PREFIX + "password-check.token-url=https://login.example.com/token",
                        PREFIX + "password-check.client-id=webapp",
                        PREFIX + "password-check.client-secret=web-secret",
                        PREFIX + "password-check.scope=openid")
                .run(context -> {
                    ScimSettings s =
                            context.getBean(ScimIdentityProviderPlugin.class).validate();
                    assertThat(s.baseUrl()).isEqualTo("https://scim.example.com/v2");
                    assertThat(s.usersEndpoint()).isEqualTo("/scim/Users");
                    assertThat(s.groupsEndpoint()).isEqualTo("/scim/Groups");
                    assertThat(s.credentials().type()).isEqualTo(ScimAuthenticationType.OAUTH2);
                    assertThat(s.credentials().tokenUrl()).isEqualTo("https://login.example.com/token");
                    assertThat(s.credentials().clientId()).isEqualTo("operaton");
                    assertThat(s.credentials().clientSecret()).isEqualTo("s3cret");
                    assertThat(s.credentials().scope()).isEqualTo("scim.read");
                    assertThat(s.mapping().userId()).isEqualTo("id");
                    assertThat(s.mapping().userFirstName()).isEqualTo("givenName");
                    assertThat(s.mapping().userLastName()).isEqualTo("familyName");
                    assertThat(s.mapping().userEmail()).isEqualTo("emails[type eq \"work\"].value");
                    assertThat(s.mapping().userGroups()).isEqualTo("memberOf");
                    assertThat(s.mapping().userBaseFilter()).isEqualTo("active eq true");
                    assertThat(s.mapping().groupId()).isEqualTo("externalId");
                    assertThat(s.mapping().groupMembers()).isEqualTo("users");
                    assertThat(s.mapping().groupBaseFilter()).isEqualTo("displayName sw \"op-\"");
                    assertThat(s.mapping().membershipSource()).isEqualTo(ScimMembershipSource.USER_GROUPS);
                    assertThat(s.pageSize()).isEqualTo(25);
                    assertThat(s.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(s.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(s.cacheTtl()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(s.cacheMaxEntries()).isEqualTo(10);
                    assertThat(s.authorizationCheckEnabled()).isFalse();
                    assertThat(s.customHeaders()).containsEntry("X-Api-Version", "2");
                    assertThat(s.passwordCheck().mode()).isEqualTo(ScimPasswordCheck.OAUTH_PASSWORD);
                    assertThat(s.passwordCheck().clientId()).isEqualTo("webapp");
                    assertThat(s.passwordCheck().clientSecret()).isEqualTo("web-secret");
                    assertThat(s.passwordCheck().scope()).isEqualTo("openid");
                });
    }

    @Test
    void failsStartupWithoutABaseUrl() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("baseUrl is required"));
    }

    @Test
    void failsStartupOnInvalidValues() {
        runner.withPropertyValues(
                        PREFIX + "base-url=scim.example.com", PREFIX + "bearer-token=t", PREFIX + "page-size=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("baseUrl must be an absolute http or https URL")
                        .hasMessageContaining("pageSize must be at least 1"));
    }

    @Test
    void failsStartupOnAnUnknownEnumValue() {
        runner.withPropertyValues(PREFIX + "base-url=https://x", PREFIX + "authentication-type=kerberos")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues(PREFIX + "enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ScimIdentityProviderPlugin.class));
    }

    @Test
    void backsOffForAUserDefinedPlugin() {
        ScimIdentityProviderPlugin custom = new ScimIdentityProviderPlugin();
        runner.withBean(ScimIdentityProviderPlugin.class, () -> custom)
                .run(context -> assertThat(context.getBean(ScimIdentityProviderPlugin.class))
                        .isSameAs(custom));
    }
}
