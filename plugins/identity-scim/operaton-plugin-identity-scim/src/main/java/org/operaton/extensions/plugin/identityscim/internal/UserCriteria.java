/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.util.List;

/**
 * The criteria of an engine user query. A {@code null} field is not part of the query.
 *
 * @param id the exact user id
 * @param ids user ids, any of
 * @param firstName the exact first name
 * @param firstNameLike a like pattern for the first name
 * @param lastName the exact last name
 * @param lastNameLike a like pattern for the last name
 * @param email the exact email address
 * @param emailLike a like pattern for the email address
 * @param groupId restricts the result to the members of this group
 */
public record UserCriteria(
        String id,
        List<String> ids,
        String firstName,
        String firstNameLike,
        String lastName,
        String lastNameLike,
        String email,
        String emailLike,
        String groupId) {}
