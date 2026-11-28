/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.example;

import java.util.List;
import java.util.Map;
import org.operaton.bpm.engine.ExternalTaskService;
import org.operaton.bpm.engine.externaltask.LockedExternalTask;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A worker for the {@code ship-order} topic that uses the engine API directly (no REST), so its calls are traced. */
@Component
public class ShippingWorker {

    static final String WORKER_ID = "shipping-worker";

    private final ExternalTaskService externalTaskService;

    ShippingWorker(ExternalTaskService externalTaskService) {
        this.externalTaskService = externalTaskService;
    }

    @Scheduled(fixedDelayString = "${example.worker.poll-ms:500}")
    public void poll() {
        List<LockedExternalTask> tasks = externalTaskService
                .fetchAndLock(5, WORKER_ID)
                .topic("ship-order", 30_000)
                .execute();
        for (LockedExternalTask task : tasks) {
            externalTaskService.complete(
                    task.getId(), WORKER_ID, Map.of("trackingNumber", "TRK-" + task.getProcessInstanceId()));
        }
    }
}
