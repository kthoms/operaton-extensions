/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.processtestcoverage.fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.extensions.plugin.processtestcoverage.ProcessTestCoverageExtension;

/** Run by ExtensionBehaviourTest through the JUnit platform; not a test of its own. */
class InstanceFieldFixture {

    @RegisterExtension
    ProcessTestCoverageExtension coverage =
            ProcessTestCoverageExtension.newBuilder().withoutReport().build();

    @Test
    void anything() {}
}
