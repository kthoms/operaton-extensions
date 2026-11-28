/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Ported from https://github.com/camunda-community-hub/camunda-platform-7-custom-batch (Apache-2.0),
 * classes org.camunda.community.batch.core.CustomBatchConfigurationJsonHelper and
 * CustomBatchConfigurationTypeAdapter; reworked: the items are plain JSON of the handler's item type
 * (Jackson) instead of Base64 encoded Java serialization (no deserialization of untrusted bytes, readable
 * in the database, schema versioned).
 */
package org.operaton.extensions.plugin.custombatch.internal;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.extensions.plugin.custombatch.CustomBatchConfiguration;

/**
 * Reads and writes the JSON document stored for a custom batch and for each of its jobs:
 *
 * <pre>{"version":1,"exclusive":true,"batchId":"…","items":[…]}</pre>
 *
 * <p>Immutable and thread-safe once the mapper is configured (a Jackson {@code ObjectMapper} is).
 *
 * @param <T> the item type
 */
public final class ConfigurationCodec<T> {

    /** The version of the document written by this class; reading any other version fails. */
    public static final int FORMAT_VERSION = 1;

    private static final String VERSION = "version";
    private static final String EXCLUSIVE = "exclusive";
    private static final String BATCH_ID = "batchId";
    private static final String ITEMS = "items";

    private final ObjectMapper mapper;
    private final Type itemType;
    private final JavaType listType;

    /**
     * Creates a codec.
     *
     * @param itemType the type of one item
     * @param mapper the mapper used for the items
     */
    public ConfigurationCodec(Type itemType, ObjectMapper mapper) {
        this.itemType = itemType;
        this.mapper = mapper;
        this.listType = mapper.getTypeFactory().constructCollectionType(List.class, mapper.constructType(itemType));
    }

    /** Serialises the configuration to UTF-8 JSON. */
    public byte[] write(CustomBatchConfiguration<T> configuration) {
        ObjectNode document = mapper.createObjectNode();
        document.put(VERSION, FORMAT_VERSION);
        document.put(EXCLUSIVE, configuration.isExclusive());
        document.put(BATCH_ID, configuration.getBatchId());
        try {
            document.set(
                    ITEMS, mapper.readTree(mapper.writerFor(listType).writeValueAsBytes(configuration.getItems())));
            return mapper.writeValueAsBytes(document);
        } catch (IOException | RuntimeException e) {
            throw new ProcessEngineException(
                    "Cannot serialise the items of a custom batch as JSON (item type " + itemType.getTypeName()
                            + "); configure the mapper in configureMapper: " + e.getMessage(),
                    e);
        }
    }

    /** Parses a document written by {@link #write}. */
    public CustomBatchConfiguration<T> read(byte[] bytes) {
        if (bytes == null) {
            throw new ProcessEngineException("The custom batch configuration is missing");
        }
        try {
            JsonNode document = mapper.readTree(bytes);
            if (document == null || !document.isObject()) {
                throw new IllegalArgumentException("expected a JSON object");
            }
            int version = required(document, VERSION).asInt(-1);
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported configuration version " + document.get(VERSION)
                        + " (this plugin reads version " + FORMAT_VERSION + ")");
            }
            boolean exclusive = required(document, EXCLUSIVE).asBoolean();
            JsonNode batchId = document.get(BATCH_ID);
            List<T> items = mapper.readerFor(listType).readValue(required(document, ITEMS));
            return new CustomBatchConfiguration<>(
                    items, exclusive, batchId == null || batchId.isNull() ? null : batchId.asText());
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
    }

    private ProcessEngineException unreadable(Exception cause) {
        return new ProcessEngineException(
                "Cannot read the custom batch configuration (item type " + itemType.getTypeName() + "): "
                        + cause.getMessage(),
                cause);
    }

    private static JsonNode required(JsonNode document, String name) {
        JsonNode node = document.get(name);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("the field '" + name + "' is missing");
        }
        return node;
    }
}
