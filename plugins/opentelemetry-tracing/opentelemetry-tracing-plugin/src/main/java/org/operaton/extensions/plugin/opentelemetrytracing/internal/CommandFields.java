/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.opentelemetrytracing.internal;

import java.lang.reflect.Field;

/** Read access to protected fields of engine commands, resolved once at startup so a missing field fails loudly. */
final class CommandFields {

    private final Field field;

    private CommandFields(Field field) {
        this.field = field;
    }

    static CommandFields of(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return new CommandFields(field);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IllegalStateException(
                    "This Operaton version does not have the field " + type.getName() + "." + name
                            + " that the OpenTelemetry tracing plugin reads",
                    e);
        }
    }

    String get(Object command) {
        try {
            return (String) field.get(command);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }
}
