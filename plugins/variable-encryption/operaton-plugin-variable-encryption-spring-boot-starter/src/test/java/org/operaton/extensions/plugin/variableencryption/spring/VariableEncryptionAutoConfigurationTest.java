/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.variableencryption.KeyProvider;
import org.operaton.extensions.plugin.variableencryption.StaticKeyProvider;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionPlugin;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class VariableEncryptionAutoConfigurationTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
    private static final String PREFIX = "operaton.extensions.plugin.variable-encryption.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VariableEncryptionAutoConfiguration.class));

    @Test
    void registersThePluginWithStaticKeys() {
        runner.withPropertyValues(
                        PREFIX + "keys.2026-01=" + KEY,
                        PREFIX + "active-key-id=2026-01",
                        PREFIX + "variable-names=iban,applicant*,regex:.*Secret")
                .run(context -> {
                    assertThat(context).hasSingleBean(VariableEncryptionPlugin.class);
                    KeyProvider provider =
                            context.getBean(VariableEncryptionPlugin.class).validate();
                    assertThat(provider.activeKeyId()).isEqualTo("2026-01");
                    assertThat(provider.key("2026-01").getEncoded()).hasSize(32);
                });
    }

    @Test
    void failsStartupWithoutAKeySource() {
        runner.withPropertyValues(PREFIX + "variable-names=iban")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("No key source"));
    }

    @Test
    void failsStartupOnAnInvalidKey() {
        runner.withPropertyValues(
                        PREFIX + "keys.k1=c2hvcnQ=", PREFIX + "active-key-id=k1", PREFIX + "variable-names=iban")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("AES-256"));
    }

    @Test
    void failsStartupWhenTheActiveKeyIsNotConfigured() {
        runner.withPropertyValues(PREFIX + "keys.k1=" + KEY, PREFIX + "active-key-id=k9")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("active key id 'k9'"));
    }

    @Test
    void failsStartupWhenNothingWouldBeEncrypted() {
        runner.withPropertyValues(
                        PREFIX + "keys.k1=" + KEY, PREFIX + "active-key-id=k1", PREFIX + "encrypt-marked-values=false")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("Nothing would be encrypted"));
    }

    @Test
    void aKeyProviderBeanReplacesTheConfiguredKeys() {
        runner.withUserConfiguration(KmsConfiguration.class)
                .withPropertyValues(PREFIX + "variable-names=iban")
                .run(context -> {
                    assertThat(context).hasSingleBean(VariableEncryptionPlugin.class);
                    assertThat(context.getBean(VariableEncryptionPlugin.class).validate())
                            .isSameAs(context.getBean(KeyProvider.class));
                });
    }

    @Test
    void keyStorePropertiesAreMapped() {
        runner.withPropertyValues(
                        PREFIX + "key-store.path=/does/not/exist.p12",
                        PREFIX + "key-store.password=changeit",
                        PREFIX + "active-key-id=k1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("/does/not/exist.p12"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues(PREFIX + "enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(VariableEncryptionPlugin.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class KmsConfiguration {
        @Bean
        KeyProvider keyProvider() {
            StaticKeyProvider delegate = new StaticKeyProvider("kms", java.util.Map.of("kms", KEY));
            return new KeyProvider() {
                @Override
                public String activeKeyId() {
                    return delegate.activeKeyId();
                }

                @Override
                public SecretKey key(String keyId) {
                    return delegate.key(keyId);
                }
            };
        }
    }
}
