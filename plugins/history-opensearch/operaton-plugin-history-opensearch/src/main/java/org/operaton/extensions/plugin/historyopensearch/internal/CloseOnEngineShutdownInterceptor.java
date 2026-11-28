/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import org.operaton.bpm.engine.impl.interceptor.Command;
import org.operaton.bpm.engine.impl.interceptor.CommandInterceptor;

/**
 * Runs an action after the engine's own shutdown command (the package-private {@code SchemaOperationProcessEngineClose}, matched by class name),
 * so a plain (non-Spring) engine releases the plugin's OpenSearch writer when it is closed. Stateless
 * apart from the action; thread-safe if the action is.
 */
public final class CloseOnEngineShutdownInterceptor extends CommandInterceptor {

    private final Runnable onClose;

    public CloseOnEngineShutdownInterceptor(Runnable onClose) {
        this.onClose = onClose;
    }

    @Override
    public <T> T execute(Command<T> command) {
        try {
            return next.execute(command);
        } finally {
            if (command.getClass().getSimpleName().equals("SchemaOperationProcessEngineClose")) {
                onClose.run();
            }
        }
    }
}
