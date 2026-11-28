/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.extensions.plugin.custombatch.CustomBatchJobHandler;
import org.operaton.extensions.plugin.custombatch.CustomBatchPlugin;
import org.operaton.extensions.plugin.custombatch.CustomBatches;
import org.operaton.extensions.plugin.custombatch.ItemCustomBatchJobHandler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CustomBatchAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(CustomBatchAutoConfiguration.class));

    @Configuration(proxyBeanMethods = false)
    static class Handlers {
        @Bean
        CustomBatchJobHandler<String> chunkHandler() {
            return new CustomBatchJobHandler<>("chunk-type", String.class) {
                @Override
                protected void executeItems(List<String> items, CommandContext commandContext) {}
            };
        }

        @Bean
        ItemCustomBatchJobHandler<Long> itemHandler() {
            return new ItemCustomBatchJobHandler<>("item-type", Long.class) {
                @Override
                protected void executeItem(Long item, CommandContext commandContext) {}
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Engine {
        @Bean
        ProcessEngine processEngine() {
            return mock(ProcessEngine.class);
        }
    }

    @Test
    void registersEveryHandlerBeanThroughOnePlugin() {
        runner.withUserConfiguration(Handlers.class).run(ctx -> {
            assertThat(ctx).hasSingleBean(CustomBatchPlugin.class);
            assertThat(ctx.getBean(CustomBatchPlugin.class).getHandlers())
                    .extracting(handler -> handler.getType())
                    .containsExactlyInAnyOrder("chunk-type", "item-type");
        });
    }

    @Test
    void registersTheHandlersInAnEngineConfiguration() {
        runner.withUserConfiguration(Handlers.class).run(ctx -> {
            StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
            ctx.getBean(CustomBatchPlugin.class).preInit(configuration);

            assertThat(configuration.getCustomBatchJobHandlers()).hasSize(2);
        });
    }

    @Test
    void worksWithoutHandlerBeans() {
        runner.run(ctx ->
                assertThat(ctx.getBean(CustomBatchPlugin.class).getHandlers()).isEmpty());
    }

    @Test
    void bindsTheHistoryTimeToLive() {
        runner.withUserConfiguration(Handlers.class)
                .withPropertyValues("operaton.extensions.plugin.custom-batch.history-time-to-live.chunk-type=P30D")
                .run(ctx -> assertThat(ctx.getBean(CustomBatchPlugin.class).getHistoryTimeToLive())
                        .containsEntry("chunk-type", "P30D"));
    }

    @Test
    void exposesTheFacadeWhenAnEngineExists() {
        runner.withUserConfiguration(Handlers.class, Engine.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(CustomBatches.class));
        runner.withUserConfiguration(Handlers.class).run(ctx -> assertThat(ctx).doesNotHaveBean(CustomBatches.class));
    }

    @Test
    void canBeDisabled() {
        runner.withUserConfiguration(Handlers.class, Engine.class)
                .withPropertyValues("operaton.extensions.plugin.custom-batch.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(CustomBatchPlugin.class);
                    assertThat(ctx).doesNotHaveBean(CustomBatches.class);
                });
    }

    @Test
    void backsOffForAnApplicationDefinedPlugin() {
        runner.withUserConfiguration(Handlers.class)
                .withBean("myPlugin", CustomBatchPlugin.class, () -> new CustomBatchPlugin())
                .run(ctx ->
                        assertThat(ctx).hasSingleBean(CustomBatchPlugin.class).hasBean("myPlugin"));
    }
}
