/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.operaton.bpm.engine.BadUserRequestException;

class ScimFiltersTest {

    @Test
    void escapesQuotesAndBackslashes() {
        assertThat(ScimFilters.eq("userName", "a\"b\\c")).isEqualTo("userName eq \"a\\\"b\\\\c\"");
    }

    @ParameterizedTest
    @CsvSource({
        "'%li%', co, li",
        "'li%', sw, li",
        "'%li', ew, li",
        "'li', eq, li",
    })
    void translatesLikePatterns(String pattern, String operator, String value) {
        assertThat(ScimFilters.like("name.givenName", pattern))
                .isEqualTo("name.givenName " + operator + " \"" + value + "\"");
    }

    @Test
    void aLoneWildcardMeansPresent() {
        assertThat(ScimFilters.like("emails.value", "%")).isEqualTo("emails.value pr");
        assertThat(ScimFilters.like("emails.value", "%%")).isEqualTo("emails.value pr");
    }

    @Test
    void aWildcardInsideThePatternFailsLoudly() {
        assertThatThrownBy(() -> ScimFilters.like("userName", "a%b"))
                .isInstanceOf(BadUserRequestException.class)
                .hasMessageContaining("a%b");
        assertThatThrownBy(() -> ScimFilters.matchesLike("a%b", "axb")).isInstanceOf(BadUserRequestException.class);
    }

    @Test
    void joinsConditionsAndSkipsEmptyOnes() {
        assertThat(ScimFilters.and(Arrays.asList(null, "a eq \"1\"", "", "b eq \"2\"")))
                .isEqualTo("a eq \"1\" and b eq \"2\"");
        assertThat(ScimFilters.and(Arrays.asList(null, ""))).isNull();
    }

    @Test
    void buildsAlternativesMembersAndBaseFilters() {
        assertThat(ScimFilters.anyOf("userName", List.of("a", "b")))
                .isEqualTo("(userName eq \"a\" or userName eq \"b\")");
        assertThat(ScimFilters.memberValue("members", "u-1")).isEqualTo("members[value eq \"u-1\"]");
        assertThat(ScimFilters.base(" active eq true ")).isEqualTo("(active eq true)");
        assertThat(ScimFilters.base("")).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "'%lic%', Alice, true",
        "'ali%', Alice, true",
        "'%ice', Alice, true",
        "'alice', ALICE, true",
        "'bob%', Alice, false",
        "'%', '', false",
        "'%', x, true",
    })
    void evaluatesLikePatternsClientSideLikeTheServer(String pattern, String value, boolean expected) {
        assertThat(ScimFilters.matchesLike(pattern, value)).isEqualTo(expected);
    }

    @Test
    void aMissingValueNeverMatches() {
        assertThat(ScimFilters.matchesLike("%a%", null)).isFalse();
    }

    @Test
    void movesTheSubAttributeOfASelectedPathInsideTheBrackets() {
        String path = "emails[type eq \"work\"].value";
        assertThat(ScimFilters.eq(path, "a@x")).isEqualTo("emails[type eq \"work\" and value eq \"a@x\"]");
        assertThat(ScimFilters.like(path, "%@x")).isEqualTo("emails[type eq \"work\" and value ew \"@x\"]");
        assertThat(ScimFilters.like(path, "%")).isEqualTo("emails[type eq \"work\" and value pr]");
        assertThat(ScimFilters.eq("emails.value", "a@x")).isEqualTo("emails.value eq \"a@x\"");
    }

    @Test
    void sortPathsHaveNoSelectors() {
        assertThat(ScimFilters.sortPath("emails[type eq \"work\"].value")).isEqualTo("emails.value");
        assertThat(ScimFilters.sortPath("name.givenName")).isEqualTo("name.givenName");
    }
}
