/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.identity.ReadOnlyIdentityProvider;

class ScimIdentityProviderPluginConfigurationTest {

    private static ScimIdentityProviderPlugin valid() {
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl("https://scim.example.com/v2/");
        plugin.setBearerToken("token");
        return plugin;
    }

    @Test
    void theDefaultsAreUsable() {
        ScimSettings settings = valid().validate();

        assertThat(settings.baseUrl()).as("trailing slash removed").isEqualTo("https://scim.example.com/v2");
        assertThat(settings.usersEndpoint()).isEqualTo("/Users");
        assertThat(settings.groupsEndpoint()).isEqualTo("/Groups");
        assertThat(settings.credentials().type()).isEqualTo(ScimAuthenticationType.BEARER);
        assertThat(settings.mapping().userId()).isEqualTo("userName");
        assertThat(settings.mapping().userFirstName()).isEqualTo("name.givenName");
        assertThat(settings.mapping().userLastName()).isEqualTo("name.familyName");
        assertThat(settings.mapping().userEmail()).isEqualTo("emails.value");
        assertThat(settings.mapping().groupId()).isEqualTo("displayName");
        assertThat(settings.mapping().groupName()).isEqualTo("displayName");
        assertThat(settings.mapping().groupMembers()).isEqualTo("members");
        assertThat(settings.mapping().membershipSource()).isEqualTo(ScimMembershipSource.GROUP_MEMBERS);
        assertThat(settings.pageSize()).isEqualTo(100);
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.authorizationCheckEnabled()).isTrue();
        assertThat(settings.passwordCheck().mode()).isEqualTo(ScimPasswordCheck.UNSUPPORTED);
    }

    @Test
    void anEmptyConfigurationListsEveryProblem() {
        assertThatThrownBy(() -> new ScimIdentityProviderPlugin().validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl is required")
                .hasMessageContaining("bearerToken is required (authenticationType=bearer)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scim.example.com", "ftp://scim.example.com", "https://", "https://x/y?a=b", "not a url"})
    void rejectsBaseUrlsThatAreNotHttp(String url) {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setBaseUrl(url);

        assertThatThrownBy(plugin::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void validatesEndpoints() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setUsersEndpoint("Users");
        plugin.setGroupsEndpoint("/");

        assertThatThrownBy(plugin::validate)
                .hasMessageContaining("usersEndpoint must start with '/'")
                .hasMessageContaining("groupsEndpoint must name a resource");

        plugin.setUsersEndpoint("/scim/Users/");
        plugin.setGroupsEndpoint("/scim/Groups");
        assertThat(plugin.validate().usersEndpoint()).isEqualTo("/scim/Users");
    }

    @Test
    void parsesEnumeratedValuesLeniently() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setAuthenticationType("OAuth2");
        plugin.setOauth2TokenUrl("https://login.example.com/token");
        plugin.setOauth2ClientId("id");
        plugin.setOauth2ClientSecret("secret");
        plugin.setMembershipSource("User-Groups");
        plugin.setPasswordCheck("oauth-password");
        plugin.setPasswordTokenUrl("https://login.example.com/token");
        plugin.setPasswordClientId("webapp");

        ScimSettings settings = plugin.validate();

        assertThat(settings.credentials().type()).isEqualTo(ScimAuthenticationType.OAUTH2);
        assertThat(settings.mapping().membershipSource()).isEqualTo(ScimMembershipSource.USER_GROUPS);
        assertThat(settings.passwordCheck().mode()).isEqualTo(ScimPasswordCheck.OAUTH_PASSWORD);
    }

    @Test
    void anUnknownEnumeratedValueNamesTheChoices() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setAuthenticationType("kerberos");
        plugin.setMembershipSource("nested");
        plugin.setPasswordCheck("ldap");

        assertThatThrownBy(plugin::validate)
                .hasMessageContaining("authenticationType must be one of [none, bearer, basic, oauth2], was 'kerberos'")
                .hasMessageContaining("membershipSource must be one of [group-members, user-groups]")
                .hasMessageContaining("passwordCheck must be one of [unsupported, oauth-password]");
    }

    @Test
    void eachAuthenticationTypeRequiresItsCredentials() {
        ScimIdentityProviderPlugin basic = valid();
        basic.setAuthenticationType("basic");
        assertThatThrownBy(basic::validate)
                .hasMessageContaining("username is required")
                .hasMessageContaining("password is required");

        ScimIdentityProviderPlugin oauth = valid();
        oauth.setAuthenticationType("oauth2");
        assertThatThrownBy(oauth::validate)
                .hasMessageContaining("oauth2TokenUrl is required")
                .hasMessageContaining("oauth2ClientId is required")
                .hasMessageContaining("oauth2ClientSecret is required");

        ScimIdentityProviderPlugin none = new ScimIdentityProviderPlugin();
        none.setBaseUrl("https://scim.example.com");
        none.setAuthenticationType("none");
        assertThat(none.validate().credentials().type()).isEqualTo(ScimAuthenticationType.NONE);
    }

    @Test
    void validatesNumbers() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setPageSize(0);
        plugin.setConnectTimeoutMillis(0);
        plugin.setRequestTimeoutMillis(-1);
        plugin.setCacheTtlMillis(-5);

        assertThatThrownBy(plugin::validate)
                .hasMessageContaining("pageSize must be at least 1")
                .hasMessageContaining("connectTimeoutMillis must be positive")
                .hasMessageContaining("requestTimeoutMillis must be positive")
                .hasMessageContaining("cacheTtlMillis must not be negative");

        ScimIdentityProviderPlugin cache = valid();
        cache.setCacheMaxEntries(0);
        assertThatThrownBy(cache::validate).hasMessageContaining("cacheMaxEntries");
        cache.setCacheTtlMillis(0);
        assertThat(cache.validate().cacheTtl()).isZero();
    }

    @Test
    void thePasswordCheckRequiresATokenEndpointAndAClient() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setPasswordCheck("oauth-password");

        assertThatThrownBy(plugin::validate)
                .hasMessageContaining("passwordTokenUrl is required")
                .hasMessageContaining("passwordClientId is required");
    }

    @Test
    void theAuthorizationHeaderIsManagedByTheAuthenticationType() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setCustomHeaders(Map.of("authorization", "Bearer other"));

        assertThatThrownBy(plugin::validate)
                .hasMessageContaining("'Authorization' is managed by authenticationType=bearer");

        plugin.setAuthenticationType("none");
        assertThat(plugin.validate().customHeaders()).containsEntry("authorization", "Bearer other");
    }

    @Test
    void userGroupsNeedsTheClaimAttribute() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setMembershipSource("user-groups");
        plugin.setUserGroupsAttribute(" ");

        assertThatThrownBy(plugin::validate).hasMessageContaining("userGroupsAttribute is required");
    }

    @Test
    void secretsAreNotPartOfTheSettingsText() {
        ScimIdentityProviderPlugin plugin = valid();
        plugin.setAuthenticationType("oauth2");
        plugin.setOauth2TokenUrl("https://login.example.com/token");
        plugin.setOauth2ClientId("operaton");
        plugin.setOauth2ClientSecret("top-secret-value");
        plugin.setPasswordCheck("oauth-password");
        plugin.setPasswordTokenUrl("https://login.example.com/token");
        plugin.setPasswordClientId("web");
        plugin.setPasswordClientSecret("another-secret");

        assertThat(plugin.validate().toString())
                .contains("operaton")
                .doesNotContain("top-secret-value")
                .doesNotContain("another-secret");
    }

    @Test
    void registersTheProviderAsTheEnginesIdentityProviderOnce() {
        ScimIdentityProviderPlugin plugin = valid();
        StandaloneInMemProcessEngineConfiguration engine = new StandaloneInMemProcessEngineConfiguration();

        plugin.preInit(engine);

        assertThat(engine.getIdentityProviderSessionFactory().getSessionType())
                .isEqualTo(ReadOnlyIdentityProvider.class);
        assertThatThrownBy(() -> plugin.preInit(new StandaloneInMemProcessEngineConfiguration()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one instance per engine");
        plugin.close();
        plugin.close();
    }

    @Test
    void anInvalidPluginRefusesToStartTheEngine() {
        assertThatThrownBy(
                        () -> new ScimIdentityProviderPlugin().preInit(new StandaloneInMemProcessEngineConfiguration()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl is required");
    }

    @Test
    void closingAnUnusedPluginIsHarmless() {
        new ScimIdentityProviderPlugin().close();
    }
}
