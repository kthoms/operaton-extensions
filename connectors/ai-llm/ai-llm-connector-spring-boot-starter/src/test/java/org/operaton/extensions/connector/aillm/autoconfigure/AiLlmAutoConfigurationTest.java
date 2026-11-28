/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.aillm.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.connector.aillm.AiLlmPlugin;
import org.operaton.extensions.connector.aillm.LlmModelConfig;
import org.operaton.extensions.connector.aillm.VariableSource;
import org.operaton.extensions.connector.aillm.tool.BeanMethodTool;
import org.operaton.extensions.connector.aillm.tool.LlmToolContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class AiLlmAutoConfigurationTest {

    /** A tool target bean. */
    public static class CustomerDirectory {
        public String lookup(String email) {
            return "customer for " + email;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        CustomerDirectory customerDirectory() {
            return new CustomerDirectory();
        }
    }

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiLlmAutoConfiguration.class))
            .withUserConfiguration(Beans.class);

    @Test
    void mapsModelsAndToolsOntoThePlugin() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.ai-llm.default-model=local",
                        "operaton.extensions.connector.ai-llm.extract-retries=3",
                        "operaton.extensions.connector.ai-llm.agent-max-iterations=7",
                        "operaton.extensions.connector.ai-llm.models.local.provider=openai",
                        "operaton.extensions.connector.ai-llm.models.local.base-url=http://localhost:11434/v1",
                        "operaton.extensions.connector.ai-llm.models.local.model=qwen2.5:1.5b",
                        "operaton.extensions.connector.ai-llm.models.local.temperature=0.1",
                        "operaton.extensions.connector.ai-llm.models.local.timeout=30s",
                        "operaton.extensions.connector.ai-llm.models.local.max-tokens=200",
                        "operaton.extensions.connector.ai-llm.models.claude.provider=anthropic",
                        "operaton.extensions.connector.ai-llm.models.claude.model=claude-sonnet",
                        "operaton.extensions.connector.ai-llm.models.claude.api-key=secret",
                        "operaton.extensions.connector.ai-llm.tools.find_customer.bean=customerDirectory",
                        "operaton.extensions.connector.ai-llm.tools.find_customer.method=lookup",
                        "operaton.extensions.connector.ai-llm.tools.find_customer.description=Finds a customer by e-mail")
                .run(context -> {
                    assertThat(context).hasSingleBean(AiLlmPlugin.class);
                    AiLlmPlugin plugin = context.getBean(AiLlmPlugin.class);
                    assertThat(plugin.getDefaultModel()).isEqualTo("local");
                    assertThat(plugin.getExtractRetries()).isEqualTo(3);
                    assertThat(plugin.getAgentMaxIterations()).isEqualTo(7);
                    LlmModelConfig local = plugin.getModels().get("local");
                    assertThat(local.getBaseUrl()).isEqualTo("http://localhost:11434/v1");
                    assertThat(local.getTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(local.getMaxTokens()).isEqualTo(200);
                    assertThat(local.getTemperature()).isEqualTo(0.1);
                    assertThat(plugin.getModels().get("claude").getApiKey()).isEqualTo("secret");
                    assertThat(plugin.getTools()).hasSize(1);
                    BeanMethodTool tool = (BeanMethodTool) plugin.getTools().get(0);
                    assertThat(tool.name()).isEqualTo("find_customer");
                    assertThat(tool.execute("{\"email\":\"a@b.c\"}", new LlmToolContext(VariableSource.none())))
                            .isEqualTo("customer for a@b.c");
                });
    }

    @Test
    void unknownToolBeanFailsTheStartup() {
        runner.withPropertyValues(
                        "operaton.extensions.connector.ai-llm.models.default.provider=openai",
                        "operaton.extensions.connector.ai-llm.models.default.model=m",
                        "operaton.extensions.connector.ai-llm.tools.t.bean=missing",
                        "operaton.extensions.connector.ai-llm.tools.t.method=x",
                        "operaton.extensions.connector.ai-llm.tools.t.description=d")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("there is no bean named 'missing'"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("operaton.extensions.connector.ai-llm.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AiLlmPlugin.class));
    }
}
