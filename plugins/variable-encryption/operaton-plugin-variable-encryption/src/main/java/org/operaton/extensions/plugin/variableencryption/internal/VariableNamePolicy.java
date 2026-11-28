/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

/**
 * Decides by variable name whether a variable is encrypted. A pattern is a glob ({@code *} any
 * characters, {@code ?} one character, everything else literal) or, with the prefix
 * {@code regex:}, a Java regular expression; both must match the whole name, case-sensitively.
 * Immutable and thread-safe. Not API.
 */
public final class VariableNamePolicy {

    private static final String REGEX_PREFIX = "regex:";

    private final List<String> patterns;
    private final List<Pattern> compiled;

    /**
     * Compiles the patterns.
     *
     * @param patterns globs or {@code regex:} expressions; may be empty
     * @throws VariableEncryptionException on a blank pattern or an invalid regular expression
     */
    public VariableNamePolicy(Collection<String> patterns) {
        List<String> kept = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                throw new VariableEncryptionException("A variable name pattern must not be blank");
            }
            String trimmed = pattern.strip();
            kept.add(trimmed);
            regexes.add(compile(trimmed));
        }
        this.patterns = List.copyOf(kept);
        this.compiled = List.copyOf(regexes);
    }

    private static Pattern compile(String pattern) {
        try {
            if (pattern.startsWith(REGEX_PREFIX)) {
                return Pattern.compile(pattern.substring(REGEX_PREFIX.length()));
            }
            StringBuilder regex = new StringBuilder();
            StringBuilder literal = new StringBuilder();
            for (char c : pattern.toCharArray()) {
                if (c == '*' || c == '?') {
                    if (literal.length() > 0) {
                        regex.append(Pattern.quote(literal.toString()));
                        literal.setLength(0);
                    }
                    regex.append(c == '*' ? ".*" : ".");
                } else {
                    literal.append(c);
                }
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
            }
            return Pattern.compile(regex.toString(), Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            throw new VariableEncryptionException(
                    "Invalid variable name pattern '" + pattern + "': " + e.getMessage(), e);
        }
    }

    /** Whether a variable with this name is designated for encryption. */
    public boolean matches(String name) {
        if (name == null) {
            return false;
        }
        for (Pattern pattern : compiled) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The configured patterns. */
    public List<String> patterns() {
        return patterns;
    }
}
