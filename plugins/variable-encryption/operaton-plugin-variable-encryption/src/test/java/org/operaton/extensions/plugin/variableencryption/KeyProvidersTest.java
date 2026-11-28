/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyProvidersTest {

    @TempDir
    Path dir;

    private static SecretKey aes(int bits) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(bits);
        return generator.generateKey();
    }

    private Path keyStore(String type, char[] password, Map<String, SecretKey> keys) throws Exception {
        KeyStore store = KeyStore.getInstance(type);
        store.load(null, password);
        for (var entry : keys.entrySet()) {
            store.setEntry(
                    entry.getKey(),
                    new KeyStore.SecretKeyEntry(entry.getValue()),
                    new KeyStore.PasswordProtection(password));
        }
        Path file = dir.resolve("keys." + type.toLowerCase());
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password);
        }
        return file;
    }

    @Test
    void staticKeysAreDecodedAndTheActiveOneIsReported() {
        StaticKeyProvider provider =
                new StaticKeyProvider("2026-02", Map.of("2026-01", TestKeys.KEY_1, "2026-02", TestKeys.KEY_2));

        assertThat(provider.activeKeyId()).isEqualTo("2026-02");
        assertThat(provider.key("2026-01").getEncoded())
                .isEqualTo("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        assertThat(provider.key("2026-01").getAlgorithm()).isEqualTo("AES");
        assertThatThrownBy(() -> provider.key("nope")).isInstanceOf(UnknownKeyException.class);
        assertThat(provider.toString()).doesNotContain(TestKeys.KEY_1, TestKeys.KEY_2);
    }

    @Test
    void staticKeysCanComeFromTheEnvironment() {
        StaticKeyProvider provider = new StaticKeyProvider(
                "k", Map.of("k", "env:APP_KEY"), name -> "APP_KEY".equals(name) ? TestKeys.KEY_1 : null);

        assertThat(provider.key("k").getEncoded()).hasSize(32);
        assertThatThrownBy(() -> new StaticKeyProvider("k", Map.of("k", "env:MISSING"), name -> null))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("'MISSING'")
                .hasMessageContaining("not set");
    }

    @Test
    void staticKeysAreValidated() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new StaticKeyProvider("k", Map.of("k", shortKey)))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("128 bits")
                .hasMessageContaining("AES-256")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortKey));
        assertThatThrownBy(() -> new StaticKeyProvider("k", Map.of("k", "not base64 !!")))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("not valid Base64");
        assertThatThrownBy(() -> new StaticKeyProvider("missing", Map.of("k", TestKeys.KEY_1)))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("active key id 'missing'");
        assertThatThrownBy(() -> new StaticKeyProvider("bad id", Map.of("bad id", TestKeys.KEY_1)))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Invalid key id");
        assertThatThrownBy(() -> new StaticKeyProvider("k", Map.of()))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("No encryption keys");
    }

    @Test
    void aPkcs12KeyStoreProvidesItsSecretKeysByAlias() throws Exception {
        SecretKey first = aes(256);
        SecretKey second = aes(256);
        Path file = keyStore("PKCS12", "changeit".toCharArray(), Map.of("key-a", first, "key-b", second));

        KeyStoreKeyProvider provider = new KeyStoreKeyProvider(file, "PKCS12", "changeit".toCharArray(), "key-b");

        assertThat(provider.activeKeyId()).isEqualTo("key-b");
        assertThat(provider.key("key-a").getEncoded()).isEqualTo(first.getEncoded());
        assertThat(provider.key("key-b").getEncoded()).isEqualTo(second.getEncoded());
        assertThatThrownBy(() -> provider.key("key-c")).isInstanceOf(UnknownKeyException.class);
    }

    @Test
    void aJceksKeyStoreWorksToo() throws Exception {
        SecretKey key = aes(256);
        Path file = keyStore("JCEKS", "pw".toCharArray(), Map.of("k1", key));

        KeyStoreKeyProvider provider = new KeyStoreKeyProvider(file, "JCEKS", "pw".toCharArray(), "k1");

        assertThat(provider.key("k1").getEncoded()).isEqualTo(key.getEncoded());
    }

    @Test
    void keyStoreProblemsFailLoudly() throws Exception {
        Path file = keyStore("PKCS12", "changeit".toCharArray(), Map.of("k1", aes(256), "weak", aes(128)));

        assertThatThrownBy(() -> new KeyStoreKeyProvider(file, "PKCS12", "wrong".toCharArray(), "k1"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Cannot read the key store");
        assertThatThrownBy(() -> new KeyStoreKeyProvider(dir.resolve("missing.p12"), "PKCS12", new char[0], "k1"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Cannot read the key store");
        assertThatThrownBy(() -> new KeyStoreKeyProvider(file, "PKCS12", "changeit".toCharArray(), "nokey"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("no secret key with the alias 'nokey'");
        // a 128 bit key is rejected as the active key, not silently used
        assertThatThrownBy(() -> new KeyStoreKeyProvider(file, "PKCS12", "changeit".toCharArray(), "weak"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("128 bits");
    }

    @Test
    void reloadPicksUpAddedKeysAndKeepsTheOldOnesOnFailure() throws Exception {
        char[] password = "changeit".toCharArray();
        SecretKey first = aes(256);
        Path file = keyStore("PKCS12", password, Map.of("k1", first));
        KeyStoreKeyProvider provider = new KeyStoreKeyProvider(file, "PKCS12", password, "k1");
        assertThatThrownBy(() -> provider.key("k2")).isInstanceOf(UnknownKeyException.class);

        SecretKey second = new SecretKeySpec(aes(256).getEncoded(), "AES");
        keyStore("PKCS12", password, Map.of("k1", first, "k2", second));
        provider.reload();
        assertThat(provider.key("k2").getEncoded()).isEqualTo(second.getEncoded());

        Files.writeString(file, "garbage");
        assertThatThrownBy(provider::reload).isInstanceOf(VariableEncryptionException.class);
        assertThat(provider.key("k2").getEncoded()).isEqualTo(second.getEncoded());
    }
}
