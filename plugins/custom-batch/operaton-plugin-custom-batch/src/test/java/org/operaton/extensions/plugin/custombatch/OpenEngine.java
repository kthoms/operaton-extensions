/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.custombatch;

import org.operaton.bpm.engine.ProcessEngine;

/** Closes a process engine at the end of a try-with-resources block. */
record OpenEngine(ProcessEngine engine) implements AutoCloseable {

    @Override
    public void close() {
        engine.close();
    }
}
