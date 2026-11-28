/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A group as read from the SCIM server.
 *
 * @param scimId the server-assigned SCIM {@code id}
 * @param id the engine group id (the mapped attribute)
 * @param name the group name, or {@code null}
 * @param resource the complete SCIM resource
 */
public record ScimGroup(String scimId, String id, String name, JsonNode resource) {}
