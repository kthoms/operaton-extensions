/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.io.Serial;
import org.operaton.bpm.engine.variable.value.TypedValue;
import org.operaton.extensions.plugin.variableencryption.EncryptedValue;
import org.operaton.spin.json.SpinJsonNode;
import org.operaton.spin.plugin.variable.value.JsonValue;
import org.operaton.spin.plugin.variable.value.XmlValue;
import org.operaton.spin.plugin.variable.value.impl.JsonValueImpl;
import org.operaton.spin.plugin.variable.value.impl.XmlValueImpl;
import org.operaton.spin.xml.SpinXmlElement;

/** Spin dependent part of {@link SpinTagging}; loaded only when the Spin plugin is present. Not API. */
final class SpinValues {

    private SpinValues() {}

    static TypedValue tag(TypedValue value) {
        if (value instanceof JsonValue json) {
            boolean deserialized = json.isDeserialized();
            return new MarkedJson(
                    deserialized ? (SpinJsonNode) json.getValue() : null,
                    deserialized ? null : json.getValueSerialized(),
                    json.getSerializationDataFormat(),
                    deserialized,
                    json.isTransient());
        }
        if (value instanceof XmlValue xml) {
            boolean deserialized = xml.isDeserialized();
            return new MarkedXml(
                    deserialized ? (SpinXmlElement) xml.getValue() : null,
                    deserialized ? null : xml.getValueSerialized(),
                    xml.getSerializationDataFormat(),
                    deserialized,
                    xml.isTransient());
        }
        return null;
    }

    static final class MarkedJson extends JsonValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        MarkedJson(
                SpinJsonNode value, String serialized, String dataFormat, boolean deserialized, boolean isTransient) {
            super(value, serialized, dataFormat, deserialized, isTransient);
        }
    }

    static final class MarkedXml extends XmlValueImpl implements EncryptedValue {
        @Serial
        private static final long serialVersionUID = 1L;

        MarkedXml(
                SpinXmlElement value, String serialized, String dataFormat, boolean deserialized, boolean isTransient) {
            super(value, serialized, dataFormat, deserialized, isTransient);
        }
    }
}
