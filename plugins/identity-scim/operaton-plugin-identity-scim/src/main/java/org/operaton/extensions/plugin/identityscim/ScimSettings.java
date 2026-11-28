/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

import java.time.Duration;
import java.util.Map;

/**
 * The validated, immutable configuration of the SCIM identity provider, produced by
 * {@link ScimIdentityProviderPlugin#validate()}. Instances are safe to share between threads.
 *
 * @param baseUrl base URL of the SCIM service without a trailing slash, for example
 *     {@code https://scim.example.com/v2}
 * @param usersEndpoint path of the users resource, starting with a slash, no trailing slash
 * @param groupsEndpoint path of the groups resource, starting with a slash, no trailing slash
 * @param credentials how the plugin authenticates to the SCIM server
 * @param mapping the attribute mapping and base filters
 * @param pageSize the {@code count} requested per page when walking through result sets
 * @param connectTimeout maximum time to establish a connection
 * @param requestTimeout maximum time for one request, including the response body
 * @param cacheTtl how long GET responses are cached; {@link Duration#ZERO} disables the cache
 * @param cacheMaxEntries maximum number of cached responses
 * @param authorizationCheckEnabled whether user and group query results are filtered by the READ
 *     permission of the authenticated user (only when the engine's authorization is enabled)
 * @param customHeaders additional headers sent with every SCIM request
 * @param passwordCheck how {@code checkPassword} is answered
 */
public record ScimSettings(
        String baseUrl,
        String usersEndpoint,
        String groupsEndpoint,
        Credentials credentials,
        Mapping mapping,
        int pageSize,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration cacheTtl,
        int cacheMaxEntries,
        boolean authorizationCheckEnabled,
        Map<String, String> customHeaders,
        PasswordCheck passwordCheck) {

    /**
     * Credentials of the plugin towards the SCIM server. The secrets are left out of {@link #toString()}.
     *
     * @param type the authentication type
     * @param bearerToken the token for {@link ScimAuthenticationType#BEARER}
     * @param username the account for {@link ScimAuthenticationType#BASIC}
     * @param password the password for {@link ScimAuthenticationType#BASIC}
     * @param tokenUrl the token endpoint for {@link ScimAuthenticationType#OAUTH2}
     * @param clientId the client id for {@link ScimAuthenticationType#OAUTH2}
     * @param clientSecret the client secret for {@link ScimAuthenticationType#OAUTH2}
     * @param scope the optional scope for {@link ScimAuthenticationType#OAUTH2}
     */
    public record Credentials(
            ScimAuthenticationType type,
            String bearerToken,
            String username,
            String password,
            String tokenUrl,
            String clientId,
            String clientSecret,
            String scope) {

        @Override
        public String toString() {
            return "Credentials[type=" + type + ", tokenUrl=" + tokenUrl + ", clientId=" + clientId
                    + ", secrets=redacted]";
        }
    }

    /**
     * Mapping of SCIM attributes to the engine's identity model. Attribute paths use dots for
     * sub-attributes ({@code name.givenName}); a multi-valued attribute on the path selects its
     * {@code primary} element, else the one of type {@code work}, else the first
     * ({@code emails.value}); {@code emails[type eq "work"].value} selects by a sub-attribute.
     *
     * @param userId SCIM attribute that becomes the user id
     * @param userFirstName SCIM attribute that becomes the first name
     * @param userLastName SCIM attribute that becomes the last name
     * @param userEmail SCIM attribute that becomes the email address
     * @param userGroups the user attribute that lists the group references
     *     ({@link ScimMembershipSource#USER_GROUPS} only)
     * @param userBaseFilter a SCIM filter every user query is restricted to; empty for none
     * @param groupId SCIM attribute that becomes the group id
     * @param groupName SCIM attribute that becomes the group name
     * @param groupMembers the group attribute that lists the members
     * @param groupBaseFilter a SCIM filter every group query is restricted to; empty for none
     * @param membershipSource where the groups of a user come from
     */
    public record Mapping(
            String userId,
            String userFirstName,
            String userLastName,
            String userEmail,
            String userGroups,
            String userBaseFilter,
            String groupId,
            String groupName,
            String groupMembers,
            String groupBaseFilter,
            ScimMembershipSource membershipSource) {}

    /**
     * Configuration of the password check. The secrets are left out of {@link #toString()}.
     *
     * @param mode how {@code checkPassword} is answered
     * @param tokenUrl the token endpoint for {@link ScimPasswordCheck#OAUTH_PASSWORD}
     * @param clientId the client id sent to the token endpoint
     * @param clientSecret the optional client secret sent to the token endpoint
     * @param scope the optional scope sent to the token endpoint
     */
    public record PasswordCheck(
            ScimPasswordCheck mode, String tokenUrl, String clientId, String clientSecret, String scope) {

        @Override
        public String toString() {
            return "PasswordCheck[mode=" + mode + ", tokenUrl=" + tokenUrl + ", clientId=" + clientId
                    + ", clientSecret=redacted]";
        }
    }
}
