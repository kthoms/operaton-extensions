/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Runs the invoice approval process on an Operaton engine with Cockpit and Tasklist. */
@SpringBootApplication
public class InvoiceApprovalApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceApprovalApplication.class, args);
    }
}
