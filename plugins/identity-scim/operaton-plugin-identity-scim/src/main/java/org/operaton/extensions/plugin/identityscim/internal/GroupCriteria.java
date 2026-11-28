/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.util.List;

/**
 * The criteria of an engine group query. A {@code null} field is not part of the query.
 *
 * @param id the exact group id
 * @param ids group ids, any of
 * @param name the exact group name
 * @param nameLike a like pattern for the group name
 * @param type the group type; every SCIM group has the type {@link ScimDirectory#GROUP_TYPE}
 * @param userId restricts the result to the groups this user is a member of
 */
public record GroupCriteria(String id, List<String> ids, String name, String nameLike, String type, String userId) {}
