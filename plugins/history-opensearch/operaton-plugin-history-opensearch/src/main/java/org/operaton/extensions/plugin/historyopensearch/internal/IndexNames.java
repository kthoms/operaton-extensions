/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.historyopensearch.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The index naming scheme: {@code <prefix>-<kind>[-<date>]}. Immutable and thread-safe. */
public final class IndexNames {

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9._-]{0,99}");
    private static final Pattern RESULT = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Instant SAMPLE = Instant.parse("2026-11-28T13:45:59Z");

    private static final Map<String, String> KIND = Map.of(
            HistoryEventCategories.PROCESS_INSTANCE, "process-instances",
            HistoryEventCategories.ACTIVITY_INSTANCE, "activity-instances",
            HistoryEventCategories.TASK_INSTANCE, "tasks",
            HistoryEventCategories.VARIABLE_INSTANCE, "variables",
            HistoryEventCategories.INCIDENT, "incidents");

    private final String prefix;
    private final DateTimeFormatter formatter;

    /** Creates the scheme; validates like {@link #validate(String, String)}. */
    public IndexNames(String prefix, String datePattern) {
        validate(prefix, datePattern);
        this.prefix = prefix;
        this.formatter = datePattern == null || datePattern.isBlank()
                ? null
                : DateTimeFormatter.ofPattern(datePattern, Locale.ROOT).withZone(ZoneOffset.UTC);
    }

    /** Fails with an {@link IllegalArgumentException} unless prefix and date pattern give valid index names. */
    public static void validate(String prefix, String datePattern) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("history-opensearch: invalid indexPrefix '" + prefix
                    + "' (lower case letters, digits, '.', '_', '-'; starts with a letter or digit; at most 100)");
        }
        if (datePattern != null && !datePattern.isBlank()) {
            String sample;
            try {
                sample = DateTimeFormatter.ofPattern(datePattern, Locale.ROOT)
                        .withZone(ZoneOffset.UTC)
                        .format(SAMPLE);
            } catch (IllegalArgumentException | java.time.DateTimeException e) {
                throw new IllegalArgumentException(
                        "history-opensearch: invalid indexDatePattern '" + datePattern + "': " + e.getMessage(), e);
            }
            if (!RESULT.matcher(sample).matches()) {
                throw new IllegalArgumentException("history-opensearch: indexDatePattern '" + datePattern
                        + "' produces '" + sample + "', which is not a valid index name part"
                        + " (lower case letters, digits, '.', '_', '-' only)");
            }
        }
    }

    /** Whether index names carry a date part. */
    public boolean timeBased() {
        return formatter != null;
    }

    /** The name an index of this category would have without date part; also the index template name. */
    public String base(String category) {
        String kind = KIND.get(category);
        if (kind == null) {
            throw new IllegalArgumentException("Unknown history category " + category);
        }
        return prefix + "-" + kind;
    }

    /** The index for the category at the given instant (UTC). */
    public String at(String category, Instant instant) {
        return formatter == null ? base(category) : base(category) + "-" + formatter.format(instant);
    }

    /** The index name pattern that matches every index of the category, for searches. */
    public String searchPattern(String category) {
        return formatter == null ? base(category) : base(category) + "-*";
    }

    /** The index patterns of the category's index template. */
    public List<String> templatePatterns(String category) {
        return formatter == null ? List.of(base(category)) : List.of(base(category), base(category) + "-*");
    }
}
