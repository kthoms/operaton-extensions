/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

/** How the plugin authenticates itself to the SCIM server. */
public enum ScimAuthenticationType {
    /** No {@code Authorization} header (servers behind a gateway or a network boundary). */
    NONE,
    /** A static bearer token. */
    BEARER,
    /** HTTP basic authentication with a service account. */
    BASIC,
    /** OAuth 2.0 client credentials grant: the plugin fetches and renews access tokens. */
    OAUTH2
}
