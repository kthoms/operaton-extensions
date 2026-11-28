/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.support;

import org.operaton.extensions.plugin.identityscim.ScimIdentityProviderPlugin;

/** Shared test fixtures: the seeded directory and a plugin pointing at it. */
public final class Scim {

    /** The bearer token the seeded server requires. */
    public static final String TOKEN = "t0ken";

    private Scim() {}

    /**
     * A server with four users and four groups:
     * <pre>
     * alice  (u-1)  groups approvers, all-staff
     * bob    (u-2)  groups clerks, all-staff
     * carol  (u-3)  groups approvers, all-staff
     * dave   (u-4)  groups operaton-admin, all-staff
     * </pre>
     */
    public static FakeScimServer seededServer() {
        FakeScimServer server = FakeScimServer.start();
        server.user("u-1", "alice", "Alice", "Anderson", "alice@example.com")
                .user("u-2", "bob", "Bob", "Brown", "bob@example.com")
                .user("u-3", "carol", "Carol", "Clark", "carol@example.com")
                .user("u-4", "dave", "Dave", "Dawson", "dave@example.com")
                .group("g-1", "approvers", "u-1", "u-3")
                .group("g-2", "clerks", "u-2")
                .group("g-3", "operaton-admin", "u-4")
                .group("g-4", "all-staff", "u-1", "u-2", "u-3", "u-4");
        server.requireAuthorization("Bearer " + TOKEN);
        return server;
    }

    /** A plugin that reads from the server with the bearer token. */
    public static ScimIdentityProviderPlugin plugin(FakeScimServer server) {
        ScimIdentityProviderPlugin plugin = new ScimIdentityProviderPlugin();
        plugin.setBaseUrl(server.baseUrl());
        plugin.setBearerToken(TOKEN);
        return plugin;
    }
}
