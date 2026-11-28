/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import java.io.Serializable;
import java.util.List;

/**
 * An order as the shop submits it. Stored as the JSON process variable {@code order}.
 *
 * @param id the order number
 * @param customer the customer's name
 * @param lines the ordered articles
 */
public record Order(String id, String customer, List<OrderLine> lines) implements Serializable {

    /**
     * One article of an order.
     *
     * @param sku the article number
     * @param quantity how many
     * @param unitPriceCents the price of one piece in cents
     */
    public record OrderLine(String sku, int quantity, long unitPriceCents) implements Serializable {}
}
