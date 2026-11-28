/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim;

/** How {@code IdentityService.checkPassword} is answered. SCIM itself defines no credential check. */
public enum ScimPasswordCheck {
    /** Not supported: {@code checkPassword} fails with an {@code IdentityProviderException}. */
    UNSUPPORTED,
    /**
     * Delegate to an OAuth 2.0 token endpoint with the resource owner password credentials grant
     * (RFC 6749, section 4.3): a token response means the password is correct.
     */
    OAUTH_PASSWORD
}
