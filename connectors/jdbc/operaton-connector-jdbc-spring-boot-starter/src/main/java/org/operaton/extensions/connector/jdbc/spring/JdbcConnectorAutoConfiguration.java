/*
 * Copyright 2026 the Operaton Extensions contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.operaton.extensions.connector.jdbc.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.JdbcConnectorPlugin;
import org.operaton.extensions.connector.jdbc.JdbcDataSourceProperties;
import org.operaton.extensions.connector.jdbc.JdbcDataSourceRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the JDBC connector's {@link JdbcConnectorPlugin} as a process engine plugin (the Operaton Spring Boot
 * starter applies every {@code ProcessEnginePlugin} bean) and builds the data sources from
 * {@link JdbcConnectorProperties}. A {@code bean-name} entry is resolved to the Spring {@code DataSource} bean, so the
 * connector can share the application's pool; no bean is used unless a property names it.
 */
@AutoConfiguration
@ConditionalOnClass(JdbcConnectorPlugin.class)
@ConditionalOnProperty(prefix = "operaton.extensions.connector.jdbc", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(JdbcConnectorProperties.class)
public class JdbcConnectorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcDataSourceRegistry jdbcDataSourceRegistry(JdbcConnectorProperties properties, BeanFactory beanFactory) {
        Map<String, DataSource> instances = new LinkedHashMap<>();
        for (Map.Entry<String, JdbcDataSourceProperties> e :
                properties.getDatasources().entrySet()) {
            String beanName = e.getValue().getBeanName();
            if (beanName != null && !beanName.isBlank()) {
                try {
                    instances.put(e.getKey(), beanFactory.getBean(beanName, DataSource.class));
                } catch (NoSuchBeanDefinitionException ex) {
                    throw new JdbcConnectorException(
                            "Data source '" + e.getKey() + "': no DataSource bean named '"
                                    + beanName + "' (operaton.extensions.connector.jdbc.datasources." + e.getKey()
                                    + ".bean-name)",
                            ex);
                }
            }
        }
        return new JdbcDataSourceRegistry(properties.getDatasources(), instances);
    }

    @Bean
    @ConditionalOnMissingBean
    public JdbcConnectorPlugin jdbcConnectorPlugin(JdbcDataSourceRegistry registry) {
        return new JdbcConnectorPlugin(registry);
    }
}
