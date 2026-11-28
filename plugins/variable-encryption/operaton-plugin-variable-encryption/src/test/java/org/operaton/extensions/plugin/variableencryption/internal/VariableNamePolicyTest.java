/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

class VariableNamePolicyTest {

    @Test
    void globsMatchTheWholeNameCaseSensitively() {
        VariableNamePolicy policy = new VariableNamePolicy(List.of("iban", "applicant*", "?ecret", "a.b"));

        assertThat(policy.matches("iban")).isTrue();
        assertThat(policy.matches("ibanNumber")).isFalse();
        assertThat(policy.matches("myiban")).isFalse();
        assertThat(policy.matches("IBAN")).isFalse();
        assertThat(policy.matches("applicant")).isTrue();
        assertThat(policy.matches("applicantProfile")).isTrue();
        assertThat(policy.matches("secret")).isTrue();
        assertThat(policy.matches("ecret")).isFalse();
        assertThat(policy.matches("a.b")).isTrue();
        assertThat(policy.matches("axb")).isFalse(); // '.' is literal in a glob
    }

    @Test
    void regexPatternsMatchTheWholeName() {
        VariableNamePolicy policy = new VariableNamePolicy(List.of("regex:(iban|bic)_\\d+", "regex:.*Secret"));

        assertThat(policy.matches("iban_1")).isTrue();
        assertThat(policy.matches("bic_22")).isTrue();
        assertThat(policy.matches("iban_")).isFalse();
        assertThat(policy.matches("xiban_1")).isFalse();
        assertThat(policy.matches("mySecret")).isTrue();
    }

    @Test
    void noPatternsMatchNothing() {
        assertThat(new VariableNamePolicy(List.of()).matches("anything")).isFalse();
        assertThat(new VariableNamePolicy(List.of("x")).matches(null)).isFalse();
    }

    @Test
    void invalidPatternsFailLoudly() {
        assertThatThrownBy(() -> new VariableNamePolicy(List.of("regex:(")))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Invalid variable name pattern");
        assertThatThrownBy(() -> new VariableNamePolicy(List.of(" ")))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("blank");
    }
}
