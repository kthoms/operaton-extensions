/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.identityscim.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.impl.identity.IdentityProviderException;

class ScimJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final JsonNode user = json("""
            {"userName":"alice","active":true,"name":{"givenName":"Alice"},
             "emails":[{"type":"home","value":"h@x"},{"type":"work","value":"w@x"},{"type":"other","value":"o@x","primary":true}],
             "phones":[{"type":"work","value":"1"},{"type":"mobile","value":"2"}],
             "tags":["a","b"],"nothing":null}""");

    @Test
    void readsSimpleAndNestedAttributes() {
        assertThat(ScimJson.text(user, "userName")).isEqualTo("alice");
        assertThat(ScimJson.text(user, "name.givenName")).isEqualTo("Alice");
        assertThat(ScimJson.text(user, "active")).isEqualTo("true");
    }

    @Test
    void absentAndNullAttributesAreNull() {
        assertThat(ScimJson.text(user, "name.familyName")).isNull();
        assertThat(ScimJson.text(user, "nothing")).isNull();
        assertThat(ScimJson.text(user, "address.city")).isNull();
    }

    @Test
    void multiValuedAttributesPreferPrimaryThenWorkThenFirst() {
        assertThat(ScimJson.text(user, "emails.value")).isEqualTo("o@x");
        assertThat(ScimJson.text(user, "emails")).isEqualTo("o@x");
        assertThat(ScimJson.text(user, "phones.value")).isEqualTo("1");
        assertThat(ScimJson.text(user, "tags")).isEqualTo("a");
    }

    @Test
    void selectsBySubAttribute() {
        assertThat(ScimJson.text(user, "emails[type eq \"home\"].value")).isEqualTo("h@x");
        assertThat(ScimJson.text(user, "phones[type eq \"mobile\"].value")).isEqualTo("2");
        assertThat(ScimJson.text(user, "phones[type eq \"fax\"].value")).isNull();
    }

    @Test
    void aPathToAComplexAttributeWithoutValueFailsLoudly() {
        assertThatThrownBy(() -> ScimJson.text(user, "name"))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("'name'");
    }

    @Test
    void listsTheElementsOfAMultiValuedAttribute() {
        assertThat(ScimJson.elements(user, "emails")).hasSize(3);
        assertThat(ScimJson.elements(user, "groups")).isEmpty();
        assertThatThrownBy(() -> ScimJson.elements(user, "userName")).isInstanceOf(IdentityProviderException.class);
    }
}
