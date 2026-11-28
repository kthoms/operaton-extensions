/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

/** Where an order is in its life; stored as the string process variable {@code status}. */
public enum OrderStatus {
    RECEIVED,
    ACCEPTED,
    REJECTED
}
