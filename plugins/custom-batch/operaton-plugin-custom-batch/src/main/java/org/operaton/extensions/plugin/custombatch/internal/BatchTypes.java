/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch.internal;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.impl.batch.BatchMonitorJobHandler;
import org.operaton.bpm.engine.impl.batch.BatchSeedJobHandler;

/** Validates the type name of a custom batch: it doubles as the batch type and the job handler type. */
public final class BatchTypes {

    /** The longest type the engine's {@code TYPE_} columns hold. */
    public static final int MAX_LENGTH = 255;

    private static final Set<String> RESERVED = reserved();

    private BatchTypes() {}

    /**
     * Fails if the type is blank, too long or taken by an engine batch type.
     *
     * @param type the proposed type
     * @return the type
     */
    public static String validate(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("The type of a custom batch must not be blank");
        }
        if (type.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "The type of a custom batch is limited to " + MAX_LENGTH + " characters, but has " + type.length());
        }
        if (RESERVED.contains(type)) {
            throw new IllegalArgumentException(
                    "The batch type '" + type + "' is used by the engine itself; choose another one");
        }
        return type;
    }

    /** The types the engine uses for its own batches and for the seed and monitor jobs, of the running version. */
    private static Set<String> reserved() {
        Set<String> types = new HashSet<>();
        types.add(BatchSeedJobHandler.TYPE);
        types.add(BatchMonitorJobHandler.TYPE);
        for (Field field : Batch.class.getFields()) {
            if (field.getName().startsWith("TYPE_")
                    && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    types.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read the engine batch type " + field.getName(), e);
                }
            }
        }
        return Set.copyOf(types);
    }
}
