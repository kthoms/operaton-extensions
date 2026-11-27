/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historykafka.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoanAnalyticsController {

    private final LoanAnalytics analytics;

    public LoanAnalyticsController(LoanAnalytics analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/loan-analytics")
    public LoanAnalytics.Snapshot analytics() {
        return analytics.snapshot();
    }
}
