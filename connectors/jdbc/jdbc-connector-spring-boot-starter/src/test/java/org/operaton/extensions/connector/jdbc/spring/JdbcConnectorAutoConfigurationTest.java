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

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.jdbc.JdbcConnectorException;
import org.operaton.extensions.connector.jdbc.JdbcConnectorPlugin;
import org.operaton.extensions.connector.jdbc.JdbcDataSourceRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JdbcConnectorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JdbcConnectorAutoConfiguration.class));

    private static final String P = "operaton.extensions.connector.jdbc.";

    @Test
    void buildsAPoolFromProperties() {
        runner.withPropertyValues(
                        P + "datasources.default.url=jdbc:h2:mem:auto1;DB_CLOSE_DELAY=-1",
                        P + "datasources.default.username=sa",
                        P + "datasources.default.maximum-pool-size=3",
                        P + "datasources.default.default-query-timeout=5s",
                        P + "datasources.default.default-max-rows=50")
                .run(context -> {
                    assertThat(context).hasSingleBean(JdbcConnectorPlugin.class);
                    JdbcDataSourceRegistry registry = context.getBean(JdbcDataSourceRegistry.class);
                    assertThat(registry.names()).containsExactly("default");
                    assertThat(registry.settings("default").getMaximumPoolSize())
                            .isEqualTo(3);
                    assertThat(registry.settings("default")
                                    .getDefaultQueryTimeout()
                                    .toSeconds())
                            .isEqualTo(5);
                    assertThat(registry.settings("default").getDefaultMaxRows()).isEqualTo(50);
                    try (var handle = registry.open("default")) {
                        assertThat(handle.connection().isValid(2)).isTrue();
                    }
                });
    }

    @Test
    void resolvesABeanNamedByTheProperty() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:auto2;DB_CLOSE_DELAY=-1",
                        P + "datasources.business.bean-name=dataSource",
                        P + "datasources.business.default-max-rows=7")
                .run(context -> {
                    JdbcDataSourceRegistry registry = context.getBean(JdbcDataSourceRegistry.class);
                    assertThat(registry.names()).containsExactly("business");
                    assertThat(registry.settings("business").getDefaultMaxRows())
                            .isEqualTo(7);
                    assertThat(context.getBean(DataSource.class)).isNotNull();
                });
    }

    @Test
    void doesNotExposeABeanThatNoPropertyNames() {
        runner.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:auto3;DB_CLOSE_DELAY=-1")
                .run(context -> assertThat(
                                context.getBean(JdbcDataSourceRegistry.class).names())
                        .isEmpty());
    }

    @Test
    void refusesToStartWhenTheNamedBeanIsMissing() {
        runner.withPropertyValues(P + "datasources.business.bean-name=nope").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
            assertThat(context.getStartupFailure()).hasStackTraceContaining(JdbcConnectorException.class.getName());
        });
    }

    @Test
    void refusesToStartForInvalidSettings() {
        runner.withPropertyValues(P + "datasources.bad.url=jdbc:h2:mem:x", P + "datasources.bad.maximum-pool-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues(P + "enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JdbcConnectorPlugin.class));
    }
}
