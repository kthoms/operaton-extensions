/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.spring;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.operaton.extensions.plugin.identityscim.ScimAuthenticationType;
import org.operaton.extensions.plugin.identityscim.ScimMembershipSource;
import org.operaton.extensions.plugin.identityscim.ScimPasswordCheck;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** Properties under {@code operaton.extensions.plugin.identity-scim}; see the plugin for their meaning. */
@ConfigurationProperties(prefix = "operaton.extensions.plugin.identity-scim")
public class ScimIdentityProviderProperties {

    /** Whether the SCIM identity provider is registered. */
    private boolean enabled = true;

    /** Base URL of the SCIM service, for example https://scim.example.com/v2. */
    private String baseUrl;

    /** Path of the users resource. */
    private String usersEndpoint = "/Users";

    /** Path of the groups resource. */
    private String groupsEndpoint = "/Groups";

    /** How the plugin authenticates to the SCIM server. */
    private ScimAuthenticationType authenticationType = ScimAuthenticationType.BEARER;

    /** Bearer token for authentication-type=bearer. */
    private String bearerToken;

    /** Service account for authentication-type=basic. */
    private String username;

    /** Service account password for authentication-type=basic. */
    private String password;

    /** Resources requested per page. */
    private int pageSize = 100;

    /** Connect timeout. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Timeout of one request. */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Filter user and group query results by the READ permission of the authenticated user. */
    private boolean authorizationCheckEnabled = true;

    /** Where the groups of a user are read from. */
    private ScimMembershipSource membershipSource = ScimMembershipSource.GROUP_MEMBERS;

    /** Additional headers sent with every SCIM request. */
    private Map<String, String> customHeaders = new HashMap<>();

    @NestedConfigurationProperty
    private final OAuth2 oauth2 = new OAuth2();

    @NestedConfigurationProperty
    private final User user = new User();

    @NestedConfigurationProperty
    private final Group group = new Group();

    @NestedConfigurationProperty
    private final Cache cache = new Cache();

    @NestedConfigurationProperty
    private final PasswordCheck passwordCheck = new PasswordCheck();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUsersEndpoint() {
        return usersEndpoint;
    }

    public void setUsersEndpoint(String usersEndpoint) {
        this.usersEndpoint = usersEndpoint;
    }

    public String getGroupsEndpoint() {
        return groupsEndpoint;
    }

    public void setGroupsEndpoint(String groupsEndpoint) {
        this.groupsEndpoint = groupsEndpoint;
    }

    public ScimAuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(ScimAuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public boolean isAuthorizationCheckEnabled() {
        return authorizationCheckEnabled;
    }

    public void setAuthorizationCheckEnabled(boolean authorizationCheckEnabled) {
        this.authorizationCheckEnabled = authorizationCheckEnabled;
    }

    public ScimMembershipSource getMembershipSource() {
        return membershipSource;
    }

    public void setMembershipSource(ScimMembershipSource membershipSource) {
        this.membershipSource = membershipSource;
    }

    public Map<String, String> getCustomHeaders() {
        return customHeaders;
    }

    public void setCustomHeaders(Map<String, String> customHeaders) {
        this.customHeaders = customHeaders;
    }

    public OAuth2 getOauth2() {
        return oauth2;
    }

    public User getUser() {
        return user;
    }

    public Group getGroup() {
        return group;
    }

    public Cache getCache() {
        return cache;
    }

    public PasswordCheck getPasswordCheck() {
        return passwordCheck;
    }

    /** OAuth 2.0 client credentials, for authentication-type=oauth2. */
    public static class OAuth2 {

        /** Token endpoint. */
        private String tokenUrl;

        /** Client id. */
        private String clientId;

        /** Client secret. */
        private String clientSecret;

        /** Optional scope. */
        private String scope;

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }

    /** Mapping of SCIM user attributes. */
    public static class User {

        /** SCIM attribute that becomes the user id. */
        private String idAttribute = "userName";

        /** SCIM attribute that becomes the first name. */
        private String firstNameAttribute = "name.givenName";

        /** SCIM attribute that becomes the last name. */
        private String lastNameAttribute = "name.familyName";

        /** SCIM attribute that becomes the email address. */
        private String emailAttribute = "emails.value";

        /** User attribute that lists group references (membership-source=user-groups). */
        private String groupsAttribute = "groups";

        /** SCIM filter every user query is restricted to. */
        private String baseFilter = "";

        public String getIdAttribute() {
            return idAttribute;
        }

        public void setIdAttribute(String idAttribute) {
            this.idAttribute = idAttribute;
        }

        public String getFirstNameAttribute() {
            return firstNameAttribute;
        }

        public void setFirstNameAttribute(String firstNameAttribute) {
            this.firstNameAttribute = firstNameAttribute;
        }

        public String getLastNameAttribute() {
            return lastNameAttribute;
        }

        public void setLastNameAttribute(String lastNameAttribute) {
            this.lastNameAttribute = lastNameAttribute;
        }

        public String getEmailAttribute() {
            return emailAttribute;
        }

        public void setEmailAttribute(String emailAttribute) {
            this.emailAttribute = emailAttribute;
        }

        public String getGroupsAttribute() {
            return groupsAttribute;
        }

        public void setGroupsAttribute(String groupsAttribute) {
            this.groupsAttribute = groupsAttribute;
        }

        public String getBaseFilter() {
            return baseFilter;
        }

        public void setBaseFilter(String baseFilter) {
            this.baseFilter = baseFilter;
        }
    }

    /** Mapping of SCIM group attributes. */
    public static class Group {

        /** SCIM attribute that becomes the group id. */
        private String idAttribute = "displayName";

        /** SCIM attribute that becomes the group name. */
        private String nameAttribute = "displayName";

        /** Group attribute that lists the members. */
        private String membersAttribute = "members";

        /** SCIM filter every group query is restricted to. */
        private String baseFilter = "";

        public String getIdAttribute() {
            return idAttribute;
        }

        public void setIdAttribute(String idAttribute) {
            this.idAttribute = idAttribute;
        }

        public String getNameAttribute() {
            return nameAttribute;
        }

        public void setNameAttribute(String nameAttribute) {
            this.nameAttribute = nameAttribute;
        }

        public String getMembersAttribute() {
            return membersAttribute;
        }

        public void setMembersAttribute(String membersAttribute) {
            this.membersAttribute = membersAttribute;
        }

        public String getBaseFilter() {
            return baseFilter;
        }

        public void setBaseFilter(String baseFilter) {
            this.baseFilter = baseFilter;
        }
    }

    /** Caching of SCIM responses. */
    public static class Cache {

        /** How long GET responses are cached; zero disables the cache. */
        private Duration ttl = Duration.ofMinutes(1);

        /** Maximum number of cached responses. */
        private int maxEntries = 500;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** How checkPassword is answered. */
    public static class PasswordCheck {

        /** unsupported (checkPassword fails) or oauth-password (resource owner password grant). */
        private ScimPasswordCheck mode = ScimPasswordCheck.UNSUPPORTED;

        /** Token endpoint for mode=oauth-password. */
        private String tokenUrl;

        /** Client id for mode=oauth-password. */
        private String clientId;

        /** Optional client secret. */
        private String clientSecret;

        /** Optional scope. */
        private String scope;

        public ScimPasswordCheck getMode() {
            return mode;
        }

        public void setMode(ScimPasswordCheck mode) {
            this.mode = mode;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }
    }
}
