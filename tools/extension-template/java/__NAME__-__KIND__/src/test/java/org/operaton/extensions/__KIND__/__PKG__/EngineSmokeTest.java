/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.__KIND__.__PKG__;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;

/** Replace with real tests of the extension against an embedded engine (in-memory H2). */
class EngineSmokeTest {

    @RegisterExtension
    static ProcessEngineExtension engine = ProcessEngineExtension.builder().build();

    @Test
    void engineStarts() {
        ProcessEngine processEngine = engine.getProcessEngine();
        assertThat(processEngine.getName()).isNotBlank();
    }
}
