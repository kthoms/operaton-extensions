/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.autoconfigure;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.extensions.plugin.custombatch.AbstractCustomBatchJobHandler;
import org.operaton.extensions.plugin.custombatch.CustomBatchPlugin;
import org.operaton.extensions.plugin.custombatch.CustomBatches;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers every {@link AbstractCustomBatchJobHandler} bean through a {@link CustomBatchPlugin} bean
 * (Operaton's Spring Boot starter adds every {@link ProcessEnginePlugin} bean to the engine) and exposes
 * a {@link CustomBatches} bean for the engine.
 *
 * <p>Handler beans are created while the engine is configured, so they must not depend on the process
 * engine or its services; give them the business collaborators they need and look up the engine in
 * the code that creates batches.
 */
@AutoConfiguration(afterName = "org.operaton.bpm.spring.boot.starter.OperatonBpmAutoConfiguration")
@ConditionalOnClass({ProcessEnginePlugin.class, CustomBatchPlugin.class})
@ConditionalOnProperty(prefix = CustomBatchProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(CustomBatchProperties.class)
public class CustomBatchAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CustomBatchPlugin customBatchPlugin(
            ObjectProvider<AbstractCustomBatchJobHandler<?>> handlers, CustomBatchProperties properties) {
        CustomBatchPlugin plugin =
                new CustomBatchPlugin(handlers.orderedStream().toList());
        plugin.setHistoryTimeToLive(properties.getHistoryTimeToLive());
        return plugin;
    }

    @Bean
    @ConditionalOnBean(ProcessEngine.class)
    @ConditionalOnMissingBean
    public CustomBatches customBatches(ProcessEngine processEngine) {
        return new CustomBatches(processEngine);
    }
}
