/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.extensions.plugin.custombatch.CustomBatchConfiguration;

class ConfigurationCodecTest {

    record Invoice(long id, String customer, List<String> tags) {}

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripsRecordItemsWithSettings() {
        ConfigurationCodec<Invoice> codec =
                new ConfigurationCodec<>(Invoice.class, JsonMapper.builder().build());
        List<Invoice> items = List.of(new Invoice(1, "ACME", List.of("a", "b")), new Invoice(2, "Globex", List.of()));

        byte[] written = codec.write(new CustomBatchConfiguration<>(items, false, "batch-1"));
        CustomBatchConfiguration<Invoice> read = codec.read(written);

        assertThat(read.getItems()).containsExactlyElementsOf(items);
        assertThat(read.isExclusive()).isFalse();
        assertThat(read.getBatchId()).isEqualTo("batch-1");
        assertThat(new String(written, StandardCharsets.UTF_8))
                .startsWith("{\"version\":1,\"exclusive\":false,\"batchId\":\"batch-1\",\"items\":[{\"id\":1");
    }

    @Test
    void roundTripsASimpleItemWithoutBatchId() {
        ConfigurationCodec<String> codec =
                new ConfigurationCodec<>(String.class, JsonMapper.builder().build());

        CustomBatchConfiguration<String> read =
                codec.read(codec.write(new CustomBatchConfiguration<>(List.of("x", "y"), true, null)));

        assertThat(read.getItems()).containsExactly("x", "y");
        assertThat(read.isExclusive()).isTrue();
        assertThat(read.getBatchId()).isNull();
    }

    @Test
    void roundTripsAGenericItemType() {
        ConfigurationCodec<Map<String, Long>> codec = new ConfigurationCodec<>(
                new TypeReference<Map<String, Long>>() {}.getType(),
                JsonMapper.builder().build());

        CustomBatchConfiguration<Map<String, Long>> read =
                codec.read(codec.write(new CustomBatchConfiguration<>(List.of(Map.of("a", 1L)), true, null)));

        assertThat(read.getItems()).containsExactly(Map.of("a", 1L));
    }

    @Test
    void anEmptyListSurvives() {
        ConfigurationCodec<Long> codec =
                new ConfigurationCodec<>(Long.class, JsonMapper.builder().build());

        assertThat(codec.read(codec.write(new CustomBatchConfiguration<>(List.<Long>of(), true, null)))
                        .getItems())
                .isEmpty();
    }

    @Test
    void refusesNullItems() {
        assertThatThrownBy(() -> new CustomBatchConfiguration<>(Arrays.asList("a", null), true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item 1");
    }

    @Test
    void refusesMalformedOrForeignDocuments() {
        ConfigurationCodec<Long> codec =
                new ConfigurationCodec<>(Long.class, JsonMapper.builder().build());

        assertThatThrownBy(() -> codec.read(bytes("not json at all {")))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("Cannot read the custom batch configuration");
        assertThatThrownBy(() -> codec.read(bytes("[1,2]")))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("expected a JSON object");
        assertThatThrownBy(() -> codec.read(bytes(""))).isInstanceOf(ProcessEngineException.class);
        assertThatThrownBy(() -> codec.read(bytes("{\"version\":2,\"exclusive\":true,\"items\":[]}")))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("unsupported configuration version 2");
        assertThatThrownBy(() -> codec.read(bytes("{\"version\":1,\"exclusive\":true}")))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("'items' is missing");
        assertThatThrownBy(() -> codec.read(bytes("{\"version\":1,\"exclusive\":true,\"items\":[1,null]}")))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("item 1");
        assertThatThrownBy(() -> codec.read(bytes("{\"version\":1,\"exclusive\":true,\"items\":[\"x\"]}")))
                .isInstanceOf(ProcessEngineException.class);
        assertThatThrownBy(() -> codec.read(null)).isInstanceOf(ProcessEngineException.class);
    }

    @Test
    void sayswhatToDoWhenTheItemTypeCannotBeWritten() {
        ConfigurationCodec<Instant> codec =
                new ConfigurationCodec<>(Instant.class, JsonMapper.builder().build());

        assertThatThrownBy(() -> codec.write(new CustomBatchConfiguration<>(List.of(Instant.now()), true, null)))
                .isInstanceOf(ProcessEngineException.class)
                .hasMessageContaining("java.time.Instant")
                .hasMessageContaining("configureMapper");
    }
}
