/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.autoconfigure;

import java.util.Map;
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin;
import org.operaton.extensions.connector.aillm.AiLlmException;
import org.operaton.extensions.connector.aillm.AiLlmPlugin;
import org.operaton.extensions.connector.aillm.tool.BeanMethodTool;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link AiLlmPlugin} as a {@link ProcessEnginePlugin} bean, which the Operaton Spring
 * Boot starter adds to the engine. Models and tools come from {@link AiLlmProperties}; a tool's bean
 * is looked up by name when the tool is called, so tools may use beans that depend on the engine.
 * The Connect plugin is registered by the Operaton starter when {@code operaton-engine-plugin-connect}
 * is on the class path.
 */
@AutoConfiguration(beforeName = "org.operaton.bpm.spring.boot.starter.OperatonBpmAutoConfiguration")
@ConditionalOnClass(ProcessEnginePlugin.class)
@ConditionalOnProperty(prefix = AiLlmProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AiLlmProperties.class)
public class AiLlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AiLlmPlugin aiLlmPlugin(AiLlmProperties properties, ListableBeanFactory beanFactory) {
        AiLlmPlugin plugin = new AiLlmPlugin();
        plugin.setModels(properties.getModels());
        plugin.setDefaultModel(properties.getDefaultModel());
        plugin.setExtractRetries(properties.getExtractRetries());
        plugin.setAgentMaxIterations(properties.getAgentMaxIterations());
        for (Map.Entry<String, AiLlmProperties.Tool> entry :
                properties.getTools().entrySet()) {
            plugin.addTool(tool(entry.getKey(), entry.getValue(), beanFactory));
        }
        return plugin;
    }

    private static BeanMethodTool tool(String name, AiLlmProperties.Tool tool, BeanFactory beanFactory) {
        String prefix = AiLlmProperties.PREFIX + ".tools." + name + ".";
        if (tool.getBean() == null || tool.getBean().isBlank()) {
            throw new AiLlmException(prefix + "bean is required");
        }
        if (tool.getMethod() == null || tool.getMethod().isBlank()) {
            throw new AiLlmException(prefix + "method is required");
        }
        if (!beanFactory.containsBean(tool.getBean())) {
            throw new AiLlmException(prefix + "bean: there is no bean named '" + tool.getBean() + "'");
        }
        Class<?> type = beanFactory.getType(tool.getBean());
        if (type == null) {
            throw new AiLlmException(prefix + "bean: the type of bean '" + tool.getBean() + "' cannot be determined");
        }
        String beanName = tool.getBean();
        return new BeanMethodTool(
                name, tool.getDescription(), type, tool.getMethod(), () -> beanFactory.getBean(beanName));
    }
}
