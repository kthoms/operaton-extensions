/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

/** Supplies the {@code Authorization} header of SCIM requests. Implementations are thread-safe. */
interface ScimAuthenticator {

    /**
     * Returns the header value for the next request.
     *
     * @return the value, or {@code null} to send no {@code Authorization} header
     */
    String authorizationHeader();

    /**
     * Whether a 401 may be cured by {@link #invalidate()} followed by a new {@link #authorizationHeader()}.
     *
     * @return {@code true} for tokens that are fetched and can expire early
     */
    boolean refreshable();

    /** Forgets the current credentials so the next {@link #authorizationHeader()} obtains fresh ones. */
    void invalidate();
}
