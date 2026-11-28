/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A user as read from the SCIM server.
 *
 * @param scimId the server-assigned SCIM {@code id}
 * @param id the engine user id (the mapped attribute)
 * @param firstName the first name, or {@code null}
 * @param lastName the last name, or {@code null}
 * @param email the email address, or {@code null}
 * @param resource the complete SCIM resource
 */
public record ScimUser(String scimId, String id, String firstName, String lastName, String email, JsonNode resource) {}
