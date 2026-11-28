/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from https://github.com/cibseven/cibseven/blob/main/engine-plugins/identity-scim/src/main/java/org/cibseven/bpm/identity/impl/scim/ScimIdentityProviderReadOnly.java (Apache-2.0)
 * Ported: the translation of engine query criteria to SCIM filter expressions (buildUserFilter,
 * buildGroupFilter): eq for exact values, co/sw/ew for the % wildcards of the like criteria, and-joined
 * conditions, escaping of backslash and quote. Changes: extracted into a stateless class; a wildcard
 * inside the value or a lone wildcard is handled instead of being passed through.
 */
package org.operaton.extensions.plugin.identityscim.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.operaton.bpm.engine.BadUserRequestException;

/** Builds SCIM filter expressions (RFC 7644, section 3.4.2.2). */
final class ScimFilters {

    private static final Pattern SELECTED_SUB_ATTRIBUTE = Pattern.compile("^(.+?)\\[(.+)]\\.([^.\\[\\]]+)$");

    private ScimFilters() {}

    /**
     * Builds {@code attribute eq "value"}.
     *
     * @param attribute the SCIM attribute path
     * @param value the value; quotes and backslashes are escaped
     * @return the condition
     */
    static String eq(String attribute, String value) {
        return expression(attribute, "eq \"" + escape(value) + "\"");
    }

    /**
     * Applies an operator to an attribute path. A path with a selector and a sub-attribute behind it
     * ({@code emails[type eq "work"].value}) is not a valid filter attribute (RFC 7644 allows the
     * sub-attribute only inside the brackets), so it becomes {@code emails[type eq "work" and value eq "x"]}.
     */
    private static String expression(String attribute, String operatorAndValue) {
        Matcher selector = SELECTED_SUB_ATTRIBUTE.matcher(attribute);
        if (selector.matches()) {
            return selector.group(1) + "[" + selector.group(2) + " and " + selector.group(3) + " " + operatorAndValue
                    + "]";
        }
        return attribute + " " + operatorAndValue;
    }

    /**
     * Removes selectors from an attribute path for use as {@code sortBy}, which has no filter syntax.
     *
     * @param attribute the SCIM attribute path
     * @return the path without {@code [..]} selectors
     */
    static String sortPath(String attribute) {
        return attribute.replaceAll("\\[[^\\]]*]", "");
    }

    /**
     * Builds the condition for an engine like pattern: {@code %x%} contains, {@code x%} starts with,
     * {@code %x} ends with, {@code x} equals, {@code %} alone present.
     *
     * @param attribute the SCIM attribute path
     * @param pattern the like pattern; {@code %} is only allowed at the start and the end
     * @return the condition
     * @throws BadUserRequestException when a {@code %} appears inside the pattern
     */
    static String like(String attribute, String pattern) {
        boolean leading = pattern.startsWith("%");
        boolean trailing = pattern.length() > 1 && pattern.endsWith("%");
        String core = pattern.substring(leading ? 1 : 0, pattern.length() - (trailing ? 1 : 0));
        if (core.contains("%")) {
            throw new BadUserRequestException("The like pattern '" + pattern
                    + "' is not supported by SCIM: '%' is only allowed at the start and at the end");
        }
        if (core.isEmpty()) {
            return expression(attribute, "pr");
        }
        String operator = leading && trailing ? "co" : leading ? "ew" : trailing ? "sw" : "eq";
        return expression(attribute, operator + " \"" + escape(core) + "\"");
    }

    /**
     * Builds {@code (attribute eq "a" or attribute eq "b")}.
     *
     * @param attribute the SCIM attribute path
     * @param values the accepted values, at least one
     * @return the condition
     */
    static String anyOf(String attribute, List<String> values) {
        List<String> alternatives = new ArrayList<>();
        for (String value : values) {
            alternatives.add(eq(attribute, value));
        }
        return "(" + String.join(" or ", alternatives) + ")";
    }

    /**
     * Builds {@code members[value eq "id"]}.
     *
     * @param membersAttribute the group attribute that lists the members
     * @param scimId the SCIM id of the member
     * @return the condition
     */
    static String memberValue(String membersAttribute, String scimId) {
        return membersAttribute + "[value eq \"" + escape(scimId) + "\"]";
    }

    /**
     * Joins conditions with {@code and}.
     *
     * @param conditions the conditions; a {@code null} or blank entry is skipped
     * @return the joined filter, or {@code null} when there is none
     */
    static String and(List<String> conditions) {
        List<String> parts = new ArrayList<>();
        for (String condition : conditions) {
            if (condition != null && !condition.isBlank()) {
                parts.add(condition);
            }
        }
        return parts.isEmpty() ? null : String.join(" and ", parts);
    }

    /**
     * Wraps a configured base filter in parentheses so it can be and-joined safely.
     *
     * @param baseFilter the configured filter, may be empty
     * @return the wrapped filter, or {@code null} when empty
     */
    static String base(String baseFilter) {
        return baseFilter == null || baseFilter.isBlank() ? null : "(" + baseFilter.trim() + ")";
    }

    /**
     * Applies an engine like pattern to a value, case-insensitively as SCIM servers compare strings.
     *
     * @param pattern the like pattern, see {@link #like(String, String)}
     * @param value the value; {@code null} never matches
     * @return whether the value matches
     * @throws BadUserRequestException when a {@code %} appears inside the pattern
     */
    static boolean matchesLike(String pattern, String value) {
        boolean leading = pattern.startsWith("%");
        boolean trailing = pattern.length() > 1 && pattern.endsWith("%");
        String core = pattern.substring(leading ? 1 : 0, pattern.length() - (trailing ? 1 : 0));
        if (core.contains("%")) {
            throw new BadUserRequestException("The like pattern '" + pattern
                    + "' is not supported by SCIM: '%' is only allowed at the start and at the end");
        }
        if (value == null) {
            return false;
        }
        if (core.isEmpty()) {
            return !value.isEmpty();
        }
        String haystack = value.toLowerCase(Locale.ROOT);
        String needle = core.toLowerCase(Locale.ROOT);
        return leading && trailing
                ? haystack.contains(needle)
                : leading
                        ? haystack.endsWith(needle)
                        : trailing ? haystack.startsWith(needle) : haystack.equals(needle);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
