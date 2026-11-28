/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Throwaway keys of the tests. */
final class TestKeys {

    static final String KEY_1 = key("0123456789abcdef0123456789abcdef");
    static final String KEY_2 = key("fedcba9876543210fedcba9876543210");
    static final String KEY_3 = key("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");

    private TestKeys() {}

    static String key(String thirtyTwoCharacters) {
        return Base64.getEncoder().encodeToString(thirtyTwoCharacters.getBytes(StandardCharsets.US_ASCII));
    }
}
