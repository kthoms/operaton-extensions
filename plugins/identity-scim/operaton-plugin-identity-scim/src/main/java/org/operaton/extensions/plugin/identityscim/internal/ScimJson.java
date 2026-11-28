/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;

/**
 * Reads attribute values from SCIM resources by path.
 *
 * <p>A path is a dotted list of attribute names ({@code name.givenName}). Where a multi-valued
 * attribute is met, the element is chosen that has {@code primary=true}, else {@code type=work},
 * else the first. A segment {@code emails[type eq "work"]} selects the element whose sub-attribute
 * has that value. A path that ends on a multi-valued or complex attribute yields the {@code value}
 * of the chosen element.
 */
final class ScimJson {

    private static final Pattern SELECTOR = Pattern.compile("^([^\\[\\]]+)\\[([^\\[\\]\\s]+) eq \"([^\"]*)\"]$");

    private ScimJson() {}

    /**
     * Returns the text value at the path.
     *
     * @param resource the SCIM resource
     * @param path the attribute path
     * @return the value, or {@code null} when the resource has none
     * @throws IdentityProviderException when the path leads to a structure without a scalar {@code value}
     */
    static String text(JsonNode resource, String path) {
        JsonNode current = resource;
        List<String> segments = split(path);
        for (int i = 0; i < segments.size(); i++) {
            current = step(current, segments.get(i));
            if (current == null || current.isNull()) {
                return null;
            }
            boolean last = i == segments.size() - 1;
            if (current.isArray() && !last) {
                current = preferred(current);
                if (current == null) {
                    return null;
                }
            }
        }
        if (current != null && current.isArray()) {
            current = preferred(current);
        }
        if (current != null && current.isObject() && current.has("value")) {
            current = current.get("value");
        }
        if (current == null || current.isNull()) {
            return null;
        }
        if (!current.isValueNode()) {
            throw new IdentityProviderException("The SCIM attribute path '" + path
                    + "' does not lead to a simple value; refine it, for example 'emails.value'");
        }
        return current.asText();
    }

    /**
     * Returns the elements of a multi-valued attribute.
     *
     * @param resource the SCIM resource
     * @param attribute the attribute name
     * @return the elements; empty when the attribute is absent
     */
    static List<JsonNode> elements(JsonNode resource, String attribute) {
        JsonNode node = resource.get(attribute);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IdentityProviderException("The SCIM attribute '" + attribute
                    + "' is expected to be multi-valued but is " + node.getNodeType());
        }
        List<JsonNode> result = new ArrayList<>();
        node.forEach(result::add);
        return result;
    }

    private static JsonNode step(JsonNode node, String segment) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Matcher selector = SELECTOR.matcher(segment);
        if (!selector.matches()) {
            return node.get(segment);
        }
        JsonNode array = node.get(selector.group(1));
        if (array == null || !array.isArray()) {
            return null;
        }
        for (JsonNode element : array) {
            JsonNode candidate = element.get(selector.group(2));
            if (candidate != null && selector.group(3).equals(candidate.asText())) {
                return element;
            }
        }
        return null;
    }

    private static JsonNode preferred(JsonNode array) {
        if (array.isEmpty()) {
            return null;
        }
        for (JsonNode element : array) {
            if (element.path("primary").asBoolean(false)) {
                return element;
            }
        }
        for (JsonNode element : array) {
            if ("work".equals(element.path("type").asText(null))) {
                return element;
            }
        }
        return array.get(0);
    }

    private static List<String> split(String path) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int brackets = 0;
        boolean quoted = false;
        for (char c : path.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '[') {
                brackets++;
            } else if (!quoted && c == ']') {
                brackets--;
            }
            if (c == '.' && brackets == 0 && !quoted) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        return segments;
    }
}
