/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.example;

import org.operaton.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Invoice recalculation as a custom batch: the starter registers the handler bean, this class wires the rest. */
@SpringBootApplication
@EnableScheduling
public class InvoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceApplication.class, args);
    }

    /**
     * The engine looks for finished batches every 30 seconds by default. Looking every second makes the
     * example (and its test) end promptly; the batch jobs themselves are not affected.
     */
    @Bean
    ProcessEnginePlugin fastBatchMonitoring() {
        return new AbstractProcessEnginePlugin() {
            @Override
            public void preInit(ProcessEngineConfigurationImpl configuration) {
                configuration.setBatchPollTime(1);
            }
        };
    }
}
