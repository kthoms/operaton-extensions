/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/connect/ai-agent/src/main/java/org/cibseven/connect/ai/agent/impl/AgentResponseImpl.java (Apache-2.0)
 * Changes: Operaton packages, the check for a JSON serializer, values for JSON documents.
 */
package org.operaton.extensions.connector.aillm.internal;

import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.variable.Variables;
import org.operaton.bpm.engine.variable.value.ObjectValue;
import org.operaton.extensions.connector.aillm.AiLlmException;

/**
 * Turns model output into variable values the engine can store. A string variable is stored in a
 * VARCHAR(4000) column; a longer answer would fail at flush time with a database error. Such text is
 * stored as an object value in the JSON data format (a byte array row instead), which reads back as
 * the same {@code String} in expressions and delegates. That needs a JSON serializer in the engine
 * (Spin JSON, {@code operaton-engine-plugin-spin} with {@code operaton-spin-dataformat-json-jackson});
 * without one the call fails with an explanation instead of the database error. Stateless.
 */
public final class LongText {

    /** Longest string the engine stores in a string variable. */
    public static final int STRING_VARIABLE_LIMIT = 4000;

    private LongText() {}

    /** A value for text: the string itself, or a JSON object value if it is longer than the limit. */
    public static Object textValue(String text, ProcessEngineConfigurationImpl configuration) {
        if (text == null || text.length() <= STRING_VARIABLE_LIMIT) {
            return text;
        }
        ObjectValue value = Variables.objectValue(text)
                .serializationDataFormat(Variables.SerializationDataFormats.JSON)
                .create();
        requireSerializer(
                value,
                configuration,
                "an answer of " + text.length() + " characters (more than " + STRING_VARIABLE_LIMIT
                        + ", the limit of a string variable)");
        return value;
    }

    /**
     * A value for a parsed JSON document: maps and lists as an object value in the JSON data format
     * (needs a JSON serializer, see above), strings as {@link #textValue}, other scalars as they are.
     */
    public static Object jsonValue(Object document, ProcessEngineConfigurationImpl configuration) {
        if (document instanceof String text) {
            return textValue(text, configuration);
        }
        if (!(document instanceof java.util.Map<?, ?>) && !(document instanceof java.util.List<?>)) {
            return document;
        }
        ObjectValue value = Variables.objectValue(document)
                .serializationDataFormat(Variables.SerializationDataFormats.JSON)
                .create();
        requireSerializer(value, configuration, "a JSON object or array (resultFormat 'json')");
        return value;
    }

    private static void requireSerializer(
            ObjectValue value, ProcessEngineConfigurationImpl configuration, String what) {
        if (configuration == null) {
            throw new AiLlmException("Cannot store " + what + ": no process engine configuration is active");
        }
        try {
            if (configuration.getVariableSerializers().findSerializerForValue(value) == null) {
                throw new AiLlmException(missingSerializer(what));
            }
        } catch (ProcessEngineException e) {
            throw new AiLlmException(missingSerializer(what), e);
        }
    }

    private static String missingSerializer(String what) {
        return "Cannot store " + what + ": the engine has no serializer for the data format application/json. "
                + "Add operaton-engine-plugin-spin and operaton-spin-dataformat-json-jackson, "
                + "or use resultFormat 'string' and a smaller maxTokens.";
    }
}
