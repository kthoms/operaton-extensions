/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.micrometermetrics.example;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Produces a steady stream of orders so the Grafana dashboard has something to show: every order
 * starts a process, large orders are approved after a moment, every seventh order carries an
 * unknown article and ends up as an incident.
 */
@Component
@ConditionalOnProperty(name = "example.simulator.enabled", havingValue = "true")
public class OrderSimulator {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final AtomicLong counter = new AtomicLong();

    public OrderSimulator(RuntimeService runtimeService, TaskService taskService) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    @Scheduled(fixedDelayString = "${example.simulator.order-interval:PT2S}")
    void placeOrder() {
        long n = counter.incrementAndGet();
        long amount = n % 3 == 0 ? 1500 : 200;
        String sku = n % 7 == 0 ? ValidateOrderDelegate.UNKNOWN_SKU : "SKU-" + (n % 5);
        runtimeService.startProcessInstanceByKey(
                "order-fulfillment", "order-" + n, Map.of("amount", amount, "sku", sku));
    }

    @Scheduled(fixedDelayString = "${example.simulator.approval-interval:PT5S}")
    void approveOrders() {
        List<Task> tasks =
                taskService.createTaskQuery().taskDefinitionKey("approve-order").list();
        tasks.forEach(task -> taskService.complete(task.getId()));
    }
}
