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
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/plugin/ScimIdentityProviderPlugin.java (Apache-2.0)
 * and .../ScimConfiguration.java (Apache-2.0)
 * Ported: registering the provider as the engine's identity provider session factory in preInit; the
 * configuration properties (server URL, endpoints, authentication, attribute mapping, base filters,
 * page size, timeouts, cache, authorization switch, custom headers).
 * Changes: validated at startup with every problem listed, enumerated values parsed from strings,
 * durations in milliseconds, group-claim membership source, password check modes, no write mode, no
 * trust-all TLS option; the property names are renamed where noted in the README.
 */
package org.operaton.extensions.plugin.identityscim;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.extensions.plugin.identityscim.internal.ScimDirectory;
import org.operaton.extensions.plugin.identityscim.internal.ScimHttpClient;
import org.operaton.extensions.plugin.identityscim.internal.ScimIdentityProviderFactory;
import org.operaton.extensions.plugin.identityscim.internal.ScimPasswordChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process engine plugin that replaces the engine's identity provider with a read-only provider
 * backed by a SCIM 2.0 server: users, groups and group membership are read from the SCIM service
 * on demand, so authorization, task assignment (candidate users and groups) and the webapps resolve
 * identities from there. Users, groups and memberships cannot be created through the engine;
 * tenants are not supported (the provider reports none).
 *
 * <p>Register it as a process engine plugin and set at least {@link #setBaseUrl(String)} and the
 * credentials ({@link #setAuthenticationType(String)} and its companions). Invalid configuration
 * fails engine startup with an {@link IllegalArgumentException} that lists every problem.
 *
 * <p>Enumerated properties take the constant name, case-insensitive, {@code -} or {@code _}
 * ({@code oauth2}, {@code group-members}, {@code oauth-password}), so they can be set from XML.
 *
 * <p>Thread-safety: configure before the engine is built; after that the instance is safe for
 * concurrent use. The engine opens one lightweight identity session per command; the HTTP client
 * and the response cache are shared per plugin instance.
 */
public class ScimIdentityProviderPlugin extends AbstractProcessEnginePlugin implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ScimIdentityProviderPlugin.class);

    private String baseUrl;
    private String usersEndpoint = "/Users";
    private String groupsEndpoint = "/Groups";

    private String authenticationType = "bearer";
    private String bearerToken;
    private String username;
    private String password;
    private String oauth2TokenUrl;
    private String oauth2ClientId;
    private String oauth2ClientSecret;
    private String oauth2Scope;

    private String userIdAttribute = "userName";
    private String userFirstNameAttribute = "name.givenName";
    private String userLastNameAttribute = "name.familyName";
    private String userEmailAttribute = "emails.value";
    private String userGroupsAttribute = "groups";
    private String userBaseFilter = "";
    private String groupIdAttribute = "displayName";
    private String groupNameAttribute = "displayName";
    private String groupMembersAttribute = "members";
    private String groupBaseFilter = "";
    private String membershipSource = "group-members";

    private int pageSize = 100;
    private long connectTimeoutMillis = 10_000;
    private long requestTimeoutMillis = 30_000;
    private long cacheTtlMillis = 60_000;
    private int cacheMaxEntries = 500;
    private boolean authorizationCheckEnabled = true;
    private Map<String, String> customHeaders = Map.of();

    private String passwordCheck = "unsupported";
    private String passwordTokenUrl;
    private String passwordClientId;
    private String passwordClientSecret;
    private String passwordScope;

    private final AtomicReference<ScimDirectory> directory = new AtomicReference<>();

    /** Base URL of the SCIM service, for example {@code https://scim.example.com/v2}. Required. */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Path of the users resource. Default {@code /Users}. */
    public void setUsersEndpoint(String usersEndpoint) {
        this.usersEndpoint = usersEndpoint;
    }

    /** Path of the groups resource. Default {@code /Groups}. */
    public void setGroupsEndpoint(String groupsEndpoint) {
        this.groupsEndpoint = groupsEndpoint;
    }

    /** {@code none}, {@code bearer} (default), {@code basic} or {@code oauth2}. */
    public void setAuthenticationType(String authenticationType) {
        this.authenticationType = authenticationType;
    }

    /** The token for {@code bearer} authentication. Never logged. */
    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    /** The service account for {@code basic} authentication. */
    public void setUsername(String username) {
        this.username = username;
    }

    /** The service account password for {@code basic} authentication. Never logged. */
    public void setPassword(String password) {
        this.password = password;
    }

    /** The token endpoint for {@code oauth2} (client credentials) authentication. */
    public void setOauth2TokenUrl(String oauth2TokenUrl) {
        this.oauth2TokenUrl = oauth2TokenUrl;
    }

    /** The client id for {@code oauth2} authentication. */
    public void setOauth2ClientId(String oauth2ClientId) {
        this.oauth2ClientId = oauth2ClientId;
    }

    /** The client secret for {@code oauth2} authentication. Never logged. */
    public void setOauth2ClientSecret(String oauth2ClientSecret) {
        this.oauth2ClientSecret = oauth2ClientSecret;
    }

    /** The optional scope requested for {@code oauth2} authentication. */
    public void setOauth2Scope(String oauth2Scope) {
        this.oauth2Scope = oauth2Scope;
    }

    /** SCIM attribute that becomes the user id. Default {@code userName}. */
    public void setUserIdAttribute(String userIdAttribute) {
        this.userIdAttribute = userIdAttribute;
    }

    /** SCIM attribute that becomes the first name. Default {@code name.givenName}. */
    public void setUserFirstNameAttribute(String userFirstNameAttribute) {
        this.userFirstNameAttribute = userFirstNameAttribute;
    }

    /** SCIM attribute that becomes the last name. Default {@code name.familyName}. */
    public void setUserLastNameAttribute(String userLastNameAttribute) {
        this.userLastNameAttribute = userLastNameAttribute;
    }

    /** SCIM attribute that becomes the email address. Default {@code emails.value}. */
    public void setUserEmailAttribute(String userEmailAttribute) {
        this.userEmailAttribute = userEmailAttribute;
    }

    /**
     * The user attribute that lists the group references, for {@code membershipSource=user-groups}.
     * Default {@code groups}.
     */
    public void setUserGroupsAttribute(String userGroupsAttribute) {
        this.userGroupsAttribute = userGroupsAttribute;
    }

    /** A SCIM filter every user query is restricted to, for example {@code active eq true}. Default none. */
    public void setUserBaseFilter(String userBaseFilter) {
        this.userBaseFilter = userBaseFilter;
    }

    /** SCIM attribute that becomes the group id. Default {@code displayName}. */
    public void setGroupIdAttribute(String groupIdAttribute) {
        this.groupIdAttribute = groupIdAttribute;
    }

    /** SCIM attribute that becomes the group name. Default {@code displayName}. */
    public void setGroupNameAttribute(String groupNameAttribute) {
        this.groupNameAttribute = groupNameAttribute;
    }

    /** The group attribute that lists the members. Default {@code members}. */
    public void setGroupMembersAttribute(String groupMembersAttribute) {
        this.groupMembersAttribute = groupMembersAttribute;
    }

    /** A SCIM filter every group query is restricted to. Default none. */
    public void setGroupBaseFilter(String groupBaseFilter) {
        this.groupBaseFilter = groupBaseFilter;
    }

    /** {@code group-members} (default) or {@code user-groups}; see {@link ScimMembershipSource}. */
    public void setMembershipSource(String membershipSource) {
        this.membershipSource = membershipSource;
    }

    /** Resources requested per page. Default 100. */
    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /** Connect timeout in milliseconds. Default 10000. */
    public void setConnectTimeoutMillis(long connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    /** Timeout of one request in milliseconds. Default 30000. */
    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /** Time to live of cached GET responses in milliseconds; 0 disables the cache. Default 60000. */
    public void setCacheTtlMillis(long cacheTtlMillis) {
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** Maximum number of cached responses. Default 500. */
    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    /**
     * Filter user and group query results by the READ permission of the authenticated user when the
     * engine's authorization is enabled. Default {@code true}.
     */
    public void setAuthorizationCheckEnabled(boolean authorizationCheckEnabled) {
        this.authorizationCheckEnabled = authorizationCheckEnabled;
    }

    /** Additional headers sent with every SCIM request. Not for credentials the plugin manages itself. */
    public void setCustomHeaders(Map<String, String> customHeaders) {
        this.customHeaders = customHeaders == null ? Map.of() : Map.copyOf(customHeaders);
    }

    /** {@code unsupported} (default) or {@code oauth-password}; see {@link ScimPasswordCheck}. */
    public void setPasswordCheck(String passwordCheck) {
        this.passwordCheck = passwordCheck;
    }

    /** The token endpoint of the password check ({@code oauth-password}). */
    public void setPasswordTokenUrl(String passwordTokenUrl) {
        this.passwordTokenUrl = passwordTokenUrl;
    }

    /** The client id of the password check ({@code oauth-password}). */
    public void setPasswordClientId(String passwordClientId) {
        this.passwordClientId = passwordClientId;
    }

    /** The optional client secret of the password check. Never logged. */
    public void setPasswordClientSecret(String passwordClientSecret) {
        this.passwordClientSecret = passwordClientSecret;
    }

    /** The optional scope of the password check. */
    public void setPasswordScope(String passwordScope) {
        this.passwordScope = passwordScope;
    }

    /**
     * Validates the configuration and returns it as immutable settings.
     *
     * @return the validated settings
     * @throws IllegalArgumentException listing every problem found
     */
    public ScimSettings validate() {
        List<String> problems = new ArrayList<>();

        String base = validUrl("baseUrl", baseUrl, problems);
        if (base != null && base.endsWith("/")) {
            base = base.replaceAll("/+$", "");
        }
        String users = endpoint("usersEndpoint", usersEndpoint, problems);
        String groups = endpoint("groupsEndpoint", groupsEndpoint, problems);

        ScimAuthenticationType authType =
                parseEnum(ScimAuthenticationType.class, "authenticationType", authenticationType, problems);
        if (authType != null) {
            switch (authType) {
                case BEARER -> require("bearerToken", bearerToken, "authenticationType=bearer", problems);
                case BASIC -> {
                    require("username", username, "authenticationType=basic", problems);
                    require("password", password, "authenticationType=basic", problems);
                }
                case OAUTH2 -> {
                    validUrl("oauth2TokenUrl", oauth2TokenUrl, problems);
                    require("oauth2ClientId", oauth2ClientId, "authenticationType=oauth2", problems);
                    require("oauth2ClientSecret", oauth2ClientSecret, "authenticationType=oauth2", problems);
                }
                case NONE -> {
                    // nothing to check
                }
            }
        }
        for (String header : customHeaders.keySet()) {
            if (header == null || header.isBlank()) {
                problems.add("customHeaders: a header name is blank");
            } else if (header.equalsIgnoreCase("Authorization") && authType != ScimAuthenticationType.NONE) {
                problems.add("customHeaders: 'Authorization' is managed by authenticationType=" + authenticationType
                        + "; use authenticationType=none to send your own");
            }
        }

        ScimMembershipSource membership =
                parseEnum(ScimMembershipSource.class, "membershipSource", membershipSource, problems);
        require("userIdAttribute", userIdAttribute, "always", problems);
        require("userFirstNameAttribute", userFirstNameAttribute, "always", problems);
        require("userLastNameAttribute", userLastNameAttribute, "always", problems);
        require("userEmailAttribute", userEmailAttribute, "always", problems);
        require("groupIdAttribute", groupIdAttribute, "always", problems);
        require("groupNameAttribute", groupNameAttribute, "always", problems);
        require("groupMembersAttribute", groupMembersAttribute, "always", problems);
        if (membership == ScimMembershipSource.USER_GROUPS) {
            require("userGroupsAttribute", userGroupsAttribute, "membershipSource=user-groups", problems);
        }

        if (pageSize < 1) {
            problems.add("pageSize must be at least 1, was " + pageSize);
        }
        if (connectTimeoutMillis < 1) {
            problems.add("connectTimeoutMillis must be positive, was " + connectTimeoutMillis);
        }
        if (requestTimeoutMillis < 1) {
            problems.add("requestTimeoutMillis must be positive, was " + requestTimeoutMillis);
        }
        if (cacheTtlMillis < 0) {
            problems.add("cacheTtlMillis must not be negative, was " + cacheTtlMillis);
        }
        if (cacheTtlMillis > 0 && cacheMaxEntries < 1) {
            problems.add("cacheMaxEntries must be at least 1 while the cache is enabled, was " + cacheMaxEntries);
        }

        ScimPasswordCheck check = parseEnum(ScimPasswordCheck.class, "passwordCheck", passwordCheck, problems);
        if (check == ScimPasswordCheck.OAUTH_PASSWORD) {
            validUrl("passwordTokenUrl", passwordTokenUrl, problems);
            require("passwordClientId", passwordClientId, "passwordCheck=oauth-password", problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid SCIM identity provider configuration: " + String.join("; ", problems));
        }

        if (base.startsWith("http://") && authType != ScimAuthenticationType.NONE) {
            LOG.warn("The SCIM base URL {} is not https: credentials are sent unencrypted", base);
        }
        return new ScimSettings(
                base,
                users,
                groups,
                new ScimSettings.Credentials(
                        authType,
                        bearerToken,
                        username,
                        password,
                        oauth2TokenUrl,
                        oauth2ClientId,
                        oauth2ClientSecret,
                        blankToNull(oauth2Scope)),
                new ScimSettings.Mapping(
                        userIdAttribute.trim(),
                        userFirstNameAttribute.trim(),
                        userLastNameAttribute.trim(),
                        userEmailAttribute.trim(),
                        userGroupsAttribute == null ? "" : userGroupsAttribute.trim(),
                        userBaseFilter == null ? "" : userBaseFilter.trim(),
                        groupIdAttribute.trim(),
                        groupNameAttribute.trim(),
                        groupMembersAttribute.trim(),
                        groupBaseFilter == null ? "" : groupBaseFilter.trim(),
                        membership),
                pageSize,
                Duration.ofMillis(connectTimeoutMillis),
                Duration.ofMillis(requestTimeoutMillis),
                Duration.ofMillis(cacheTtlMillis),
                cacheMaxEntries,
                authorizationCheckEnabled,
                Map.copyOf(new LinkedHashMap<>(customHeaders)),
                new ScimSettings.PasswordCheck(
                        check, passwordTokenUrl, passwordClientId, passwordClientSecret, blankToNull(passwordScope)));
    }

    @Override
    public void preInit(ProcessEngineConfigurationImpl configuration) {
        ScimSettings settings = validate();
        ScimHttpClient client = ScimHttpClient.create(settings);
        ScimDirectory scimDirectory = new ScimDirectory(settings, client);
        if (!directory.compareAndSet(null, scimDirectory)) {
            throw new IllegalStateException(
                    "This ScimIdentityProviderPlugin instance is already registered with an engine; use one instance per engine");
        }
        ScimPasswordChecker checker = ScimPasswordChecker.create(settings, scimDirectory, client);
        configuration.setIdentityProviderSessionFactory(
                new ScimIdentityProviderFactory(settings, scimDirectory, checker));
        LOG.info(
                "SCIM identity provider for engine {} reads users and groups from {}",
                configuration.getProcessEngineName(),
                settings.baseUrl());
    }

    /** Drops the cached SCIM responses and the cached access token. Safe to call at any time. */
    @Override
    public void close() {
        ScimDirectory current = directory.get();
        if (current != null) {
            current.close();
        }
    }

    private static String validUrl(String name, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return null;
        }
        String trimmed = value.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            problems.add(name + " is not a valid URL: " + e.getMessage());
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            problems.add(name + " must be an absolute http or https URL, was '" + trimmed + "'");
            return null;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            problems.add(name + " must not contain a query or fragment, was '" + trimmed + "'");
            return null;
        }
        return trimmed;
    }

    private static String endpoint(String name, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return null;
        }
        String path = value.trim();
        if (!path.startsWith("/")) {
            problems.add(name + " must start with '/', was '" + path + "'");
            return null;
        }
        String stripped = path.replaceAll("/+$", "");
        if (stripped.isEmpty()) {
            problems.add(name + " must name a resource, was '" + path + "'");
            return null;
        }
        return stripped;
    }

    private static void require(String name, String value, String when, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required (" + when + ")");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            problems.add(name + " is required");
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        problems.add(name + " must be one of "
                + List.of(type.getEnumConstants()).stream()
                        .map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                        .toList()
                + ", was '" + value + "'");
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
