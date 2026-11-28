/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.typedvariables;

/** How objects, lists, sets and maps are stored in a process variable. */
public enum SerializationFormat {

    /** JSON through Spin when the Spin plugin and its Jackson data format are on the classpath, else Java. */
    AUTO,

    /** JSON through Spin; fails loudly when Spin or its JSON data format is missing. */
    JSON,

    /** Java serialization; the stored classes must be {@link java.io.Serializable}. */
    JAVA
}
