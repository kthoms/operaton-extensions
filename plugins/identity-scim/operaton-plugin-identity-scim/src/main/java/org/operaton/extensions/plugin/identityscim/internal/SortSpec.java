/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

/**
 * One ordering criterion of a query.
 *
 * @param field the field to order by
 * @param ascending the direction
 */
public record SortSpec(Field field, boolean ascending) {

    /** The fields queries can be ordered by. */
    public enum Field {
        /** The user or group id. */
        ID,
        /** The user's first name. */
        FIRST_NAME,
        /** The user's last name. */
        LAST_NAME,
        /** The user's email address. */
        EMAIL,
        /** The group name. */
        NAME,
        /** The group type, identical for all SCIM groups. */
        TYPE
    }
}
