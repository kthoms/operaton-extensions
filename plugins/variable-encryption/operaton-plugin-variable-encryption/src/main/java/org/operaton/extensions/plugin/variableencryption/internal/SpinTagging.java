/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import org.operaton.bpm.engine.variable.value.TypedValue;

/**
 * Tagging of the Spin plugin's JSON and XML values. The Spin classes are only touched (through
 * {@link SpinValues}) once {@link #available()} found the plugin on the classpath, so the plugin
 * works without Spin. Not API.
 */
public final class SpinTagging {

    private static final boolean AVAILABLE = detect();

    private SpinTagging() {}

    private static boolean detect() {
        try {
            Class.forName(
                    "org.operaton.spin.plugin.variable.value.impl.JsonValueImpl",
                    false,
                    SpinTagging.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Whether the Spin plugin is on the classpath. */
    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Tags a JSON or XML value.
     *
     * @param value any typed value
     * @return the tagged value, or {@code null} if it is neither a JSON nor an XML value
     */
    public static TypedValue tag(TypedValue value) {
        return SpinValues.tag(value);
    }
}
