/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.connector.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S3StoreConfigTest {

    private static S3StoreConfig valid() {
        S3StoreConfig config = new S3StoreConfig();
        config.setBucket("b");
        config.setRegion("eu-central-1");
        return config;
    }

    @Test
    void defaultsAreValid() {
        assertThatCode(() -> valid().validate("s")).doesNotThrowAnyException();
    }

    @Test
    void blankValuesCountAsUnset() {
        S3StoreConfig config = valid();
        config.setEndpoint("  ");
        config.setAccessKey("");
        assertThat(config.getEndpoint()).isNull();
        assertThat(config.getAccessKey()).isNull();
    }

    @Test
    void rejectsHalfConfiguredStaticCredentials() {
        S3StoreConfig config = valid();
        config.setAccessKey("key");
        assertThatThrownBy(() -> config.validate("docs"))
                .isInstanceOf(S3ConnectorException.class)
                .hasMessageContaining("'docs'")
                .hasMessageContaining("access-key and secret-key");
    }

    @Test
    void rejectsStaticCredentialsTogetherWithProfile() {
        S3StoreConfig config = valid();
        config.setAccessKey("key");
        config.setSecretKey("secret");
        config.setProfile("dev");
        assertThatThrownBy(() -> config.validate("docs")).hasMessageContaining("mutually exclusive");
    }

    @Test
    void rejectsMalformedEndpoint() {
        S3StoreConfig config = valid();
        config.setEndpoint("minio:9000");
        assertThatThrownBy(() -> config.validate("docs")).hasMessageContaining("endpoint is not a valid URL");
    }

    @Test
    void rejectsNonPositiveLimitsAndTooLongExpiry() {
        S3StoreConfig size = valid();
        size.setMaxDownloadBytes(0);
        assertThatThrownBy(() -> size.validate("s")).hasMessageContaining("max-download-bytes");

        S3StoreConfig expiry = valid();
        expiry.setPresignExpiry(Duration.ofDays(8));
        assertThatThrownBy(() -> expiry.validate("s")).hasMessageContaining("7 days");

        S3StoreConfig timeout = valid();
        timeout.setSocketTimeout(Duration.ZERO);
        assertThatThrownBy(() -> timeout.validate("s")).hasMessageContaining("socket-timeout");
    }

    @Test
    void toStringNeverShowsSecrets() {
        S3StoreConfig config = valid();
        config.setAccessKey("AKIAEXAMPLEKEY");
        config.setSecretKey("very-secret-value");
        config.setSessionToken("token-value");
        assertThat(config.toString()).doesNotContain("AKIAEXAMPLEKEY", "very-secret-value", "token-value");
    }

    @Test
    void aStoreWithoutAnyRegionFailsWhenFirstUsedNotWhenRegistered() {
        S3StoreConfig config = new S3StoreConfig();
        config.setAccessKey("a");
        config.setSecretKey("b");
        try (S3StoreRegistry registry = new S3StoreRegistry(Map.of("noregion", config), null)) {
            assertThatThrownBy(() -> registry.store(null))
                    .isInstanceOf(S3ConnectorException.class)
                    .hasMessageContaining("S3 store 'noregion'")
                    .hasMessageContaining("region");
        }
    }

    @Test
    void registryRefusesToStartWithoutStores() {
        assertThatThrownBy(() -> new S3StoreRegistry(Map.of(), null)).hasMessageContaining("No S3 store configured");
    }

    @Test
    void registryRefusesUnknownDefaultStore() {
        assertThatThrownBy(() -> new S3StoreRegistry(Map.of("a", valid()), "b"))
                .hasMessageContaining("default S3 store 'b'");
    }

    @Test
    void registryResolvesStoresWithoutTouchingTheNetwork() {
        try (S3StoreRegistry registry = new S3StoreRegistry(Map.of("only", valid()), null)) {
            assertThat(registry.store(null).name()).isEqualTo("only");
            assertThat(registry.store("only")).isSameAs(registry.store("only"));
            assertThatThrownBy(() -> registry.store("other"))
                    .hasMessageContaining("Unknown S3 store 'other'")
                    .hasMessageContaining("only");
        }
    }

    @Test
    void registryNeedsADefaultWhenSeveralStoresExist() {
        try (S3StoreRegistry registry = new S3StoreRegistry(Map.of("a", valid(), "b", valid()), null)) {
            assertThatThrownBy(() -> registry.store(null)).hasMessageContaining("no default store");
        }
    }
}
