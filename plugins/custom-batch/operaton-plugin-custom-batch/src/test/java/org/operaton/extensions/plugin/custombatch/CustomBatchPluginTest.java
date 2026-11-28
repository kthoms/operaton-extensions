/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;

class CustomBatchPluginTest {

    private static StandaloneInMemProcessEngineConfiguration configuration() {
        StandaloneInMemProcessEngineConfiguration configuration = new StandaloneInMemProcessEngineConfiguration();
        configuration.setProcessEngineName("plugin-" + UUID.randomUUID());
        configuration.setJdbcUrl("jdbc:h2:mem:plugin-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        configuration.setJobExecutorActivate(false);
        return configuration;
    }

    private static CustomBatchJobHandler<Long> handler(String type) {
        return new CustomBatchJobHandler<>(type, Long.class) {
            @Override
            protected void executeItems(List<Long> items, CommandContext commandContext) {}
        };
    }

    @Test
    void registersTheHandlersWithTheEngine() {
        CustomBatchJobHandler<Long> first = handler("first");
        CustomBatchJobHandler<Long> second = handler("second");
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(first).addHandler(second));

        try (OpenEngine engineHandle = new OpenEngine(configuration.buildProcessEngine())) {
            ProcessEngine engine = engineHandle.engine();
            assertThat(configuration.getBatchHandlers())
                    .containsEntry("first", first)
                    .containsEntry("second", second);
            assertThat(configuration.getJobHandlers())
                    .containsEntry("first", first)
                    .containsEntry("second", second);
        }
    }

    @Test
    void keepsHandlersAnotherPluginRegisteredBeforeIt() {
        CustomBatchJobHandler<Long> mine = handler("mine");
        CustomBatchJobHandler<Long> theirs = handler("theirs");
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(mine));
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(theirs));

        try (OpenEngine engineHandle = new OpenEngine(configuration.buildProcessEngine())) {
            ProcessEngine engine = engineHandle.engine();
            assertThat(configuration.getBatchHandlers()).containsKeys("mine", "theirs");
        }
    }

    @Test
    void refusesTwoHandlersOfOneType() {
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(handler("same"), handler("same")));

        assertThatThrownBy(configuration::buildProcessEngine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two custom batch handlers use the type 'same'");
    }

    @Test
    void refusesATypeAnotherPluginAlreadyRegistered() {
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(handler("clash")));
        configuration.getProcessEnginePlugins().add(new CustomBatchPlugin(handler("clash")));

        assertThatThrownBy(configuration::buildProcessEngine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'clash'")
                .hasMessageContaining("unique");
    }

    @Test
    void refusesTypesOfTheEngineAndInvalidTypes() {
        assertThatThrownBy(() -> handler(Batch.TYPE_PROCESS_INSTANCE_MIGRATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("used by the engine itself");
        assertThatThrownBy(() -> handler("batch-seed-job")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler("batch-monitor-job")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler("x".repeat(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsTheHistoryTimeToLiveToTheEngineConfiguration() {
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        CustomBatchPlugin plugin = new CustomBatchPlugin(handler("ttl"));
        plugin.setHistoryTimeToLive(Map.of("ttl", "P7D"));
        configuration.getProcessEnginePlugins().add(plugin);

        try (OpenEngine engineHandle = new OpenEngine(configuration.buildProcessEngine())) {
            ProcessEngine engine = engineHandle.engine();
            assertThat(configuration.getBatchOperationsForHistoryCleanup()).containsEntry("ttl", "P7D");
            assertThat(configuration.getParsedBatchOperationsForHistoryCleanup())
                    .containsEntry("ttl", 7);
        }
    }

    @Test
    void refusesATimeToLiveForAnUnknownType() {
        StandaloneInMemProcessEngineConfiguration configuration = configuration();
        CustomBatchPlugin plugin = new CustomBatchPlugin(handler("known"));
        plugin.setHistoryTimeToLive(Map.of("unknown", "P7D"));
        configuration.getProcessEnginePlugins().add(plugin);

        assertThatThrownBy(configuration::buildProcessEngine)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'unknown'")
                .hasMessageContaining("known");
    }

    @Test
    void anItemTypeJacksonCannotHandleNeedsAModuleFromConfigureMapper() {
        CustomBatchJobHandler<Instant> plain = new CustomBatchJobHandler<>("plain-instant", Instant.class) {
            @Override
            protected void executeItems(List<Instant> items, CommandContext commandContext) {}
        };
        CustomBatchJobHandler<Instant> adapted = new CustomBatchJobHandler<>("adapted-instant", Instant.class) {
            @Override
            protected void executeItems(List<Instant> items, CommandContext commandContext) {}

            @Override
            protected JsonMapper.Builder configureMapper(JsonMapper.Builder builder) {
                SimpleModule instants = new SimpleModule()
                        .addSerializer(Instant.class, new ToStringSerializer(Instant.class))
                        .addDeserializer(Instant.class, new StdScalarDeserializer<>(Instant.class) {
                            @Override
                            public Instant deserialize(JsonParser parser, DeserializationContext context)
                                    throws java.io.IOException {
                                return Instant.parse(parser.getValueAsString());
                            }
                        });
                return builder.addModule(instants);
            }
        };
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        CustomBatchConfiguration<Instant> configuration = new CustomBatchConfiguration<>(List.of(now), true, null);

        assertThatThrownBy(() -> plain.writeConfiguration(configuration)).hasMessageContaining("configureMapper");
        assertThat(adapted.readConfiguration(adapted.writeConfiguration(configuration))
                        .getItems())
                .containsExactly(now);
    }
}
