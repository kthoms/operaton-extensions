/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

import org.operaton.extensions.plugin.typedvariables.internal.ObjectCodecs;

/** Exposes the internal Spin detection to the tests of this package. */
final class ObjectVariablesTestSupport {

    private ObjectVariablesTestSupport() {}

    static boolean spinPresent() {
        return ObjectCodecs.jsonAvailable();
    }
}
