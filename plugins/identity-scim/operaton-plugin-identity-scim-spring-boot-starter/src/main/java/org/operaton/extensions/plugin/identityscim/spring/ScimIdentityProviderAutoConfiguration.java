/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.spring;

import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link ScimIdentityProviderPlugin} as a process engine plugin bean (the Operaton
 * Spring Boot starter picks up every {@code ProcessEnginePlugin} bean). Invalid configuration fails
 * the application context at startup. Mapping only; no logic.
 */
@AutoConfiguration
@ConditionalOnClass(ScimIdentityProviderPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.plugin.identity-scim", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ScimIdentityProviderProperties.class)
public class ScimIdentityProviderAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ScimIdentityProviderPlugin scimIdentityProviderPlugin(ScimIdentityProviderProperties properties) {
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl(properties.getBaseUrl());
        plugin.setUsersEndpoint(properties.getUsersEndpoint());
        plugin.setGroupsEndpoint(properties.getGroupsEndpoint());
        plugin.setAuthenticationType(name(properties.getAuthenticationType()));
        plugin.setBearerToken(properties.getBearerToken());
        plugin.setUsername(properties.getUsername());
        plugin.setPassword(properties.getPassword());
        plugin.setOauth2TokenUrl(properties.getOauth2().getTokenUrl());
        plugin.setOauth2ClientId(properties.getOauth2().getClientId());
        plugin.setOauth2ClientSecret(properties.getOauth2().getClientSecret());
        plugin.setOauth2Scope(properties.getOauth2().getScope());
        plugin.setUserIdAttribute(properties.getUser().getIdAttribute());
        plugin.setUserFirstNameAttribute(properties.getUser().getFirstNameAttribute());
        plugin.setUserLastNameAttribute(properties.getUser().getLastNameAttribute());
        plugin.setUserEmailAttribute(properties.getUser().getEmailAttribute());
        plugin.setUserGroupsAttribute(properties.getUser().getGroupsAttribute());
        plugin.setUserBaseFilter(properties.getUser().getBaseFilter());
        plugin.setGroupIdAttribute(properties.getGroup().getIdAttribute());
        plugin.setGroupNameAttribute(properties.getGroup().getNameAttribute());
        plugin.setGroupMembersAttribute(properties.getGroup().getMembersAttribute());
        plugin.setGroupBaseFilter(properties.getGroup().getBaseFilter());
        plugin.setMembershipSource(name(properties.getMembershipSource()));
        plugin.setPageSize(properties.getPageSize());
        plugin.setConnectTimeoutMillis(properties.getConnectTimeout().toMillis());
        plugin.setRequestTimeoutMillis(properties.getRequestTimeout().toMillis());
        plugin.setCacheTtlMillis(properties.getCache().getTtl().toMillis());
        plugin.setCacheMaxEntries(properties.getCache().getMaxEntries());
        plugin.setAuthorizationCheckEnabled(properties.isAuthorizationCheckEnabled());
        plugin.setCustomHeaders(properties.getCustomHeaders());
        plugin.setPasswordCheck(name(properties.getPasswordCheck().getMode()));
        plugin.setPasswordTokenUrl(properties.getPasswordCheck().getTokenUrl());
        plugin.setPasswordClientId(properties.getPasswordCheck().getClientId());
        plugin.setPasswordClientSecret(properties.getPasswordCheck().getClientSecret());
        plugin.setPasswordScope(properties.getPasswordCheck().getScope());
        plugin.validate();
        return plugin;
    }

    private static String name(Enum<?> value) {
        return value.name();
    }
}
