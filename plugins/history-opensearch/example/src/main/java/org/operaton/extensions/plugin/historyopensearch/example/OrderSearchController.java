/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.example;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST view of the order search: {@code /api/orders}, {@code /api/orders/summary}, {@code /api/orders/open-tasks}. */
@RestController
public class OrderSearchController {

    private final OrderSearch search;

    public OrderSearchController(OrderSearch search) {
        this.search = search;
    }

    @GetMapping("/api/orders")
    public List<OrderSearch.OrderView> orders(@RequestParam("customer") String customer) {
        return search.findByCustomer(customer);
    }

    @GetMapping("/api/orders/summary")
    public OrderSearch.Summary summary() {
        return search.summary();
    }

    @GetMapping("/api/orders/open-tasks")
    public List<OrderSearch.OpenTask> openTasks() {
        return search.openTasks();
    }
}
