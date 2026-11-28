/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables.example;

import java.util.Map;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Where the shop submits orders: {@code POST /orders} with the order as JSON starts the process. */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> submit(@RequestBody Order order) {
        ProcessInstance instance = orderService.submit(order);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("processInstanceId", instance.getId(), "orderId", order.id()));
    }
}
