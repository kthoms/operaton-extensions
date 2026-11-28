/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

/** Where the groups of a user are read from. */
public enum ScimMembershipSource {
    /**
     * Ask the server for the groups that list the user as a member:
     * {@code GET /Groups?filter=members[value eq "<user id>"]}. One request, needs a server that
     * supports filtering on {@code members}.
     */
    GROUP_MEMBERS,
    /**
     * Read the user's {@code groups} claim (RFC 7643, section 4.1.2) and fetch each referenced group.
     * For servers that populate the claim but cannot filter groups by member (for example Microsoft
     * Entra ID); one request per group, cached.
     */
    USER_GROUPS
}
