/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.fixtures;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.extensions.plugin.processtestcoverage.ProcessTestCoverageExtension;

/** Run by ExtensionBehaviourTest through the JUnit platform; not a test of its own. */
class NoProcessFixture {

    @RegisterExtension
    static ProcessTestCoverageExtension coverage = ProcessTestCoverageExtension.newBuilder()
            .reportDirectory(Path.of(System.getProperty("ptc.fixture.dir")))
            .withMinimumCoverage(0.1)
            .build();

    @Test
    void doesNothing() {}
}
