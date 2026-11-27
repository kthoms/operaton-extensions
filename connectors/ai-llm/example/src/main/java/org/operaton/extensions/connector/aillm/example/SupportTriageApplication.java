/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Classifies incoming support e-mails with an LLM and routes them to the right team. */
@SpringBootApplication
public class SupportTriageApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportTriageApplication.class, args);
    }
}
