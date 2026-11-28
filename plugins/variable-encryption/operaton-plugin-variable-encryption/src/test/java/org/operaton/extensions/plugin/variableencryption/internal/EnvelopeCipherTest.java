/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.operaton.extensions.plugin.variableencryption.StaticKeyProvider;
import org.operaton.extensions.plugin.variableencryption.UnknownKeyException;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

class EnvelopeCipherTest {

    private static final String K1 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="; // 0123456789abcdef x2
    private static final String K2 = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA="; // fedcba9876543210 x2

    private final EnvelopeCipher cipher = new EnvelopeCipher(new StaticKeyProvider("k1", Map.of("k1", K1, "k2", K2)));

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundTripsAndDoesNotContainThePlaintext() {
        byte[] plaintext = bytes("DE89 3704 0044 0532 0130 00");

        EnvelopeCipher.Sealed sealed = cipher.seal(plaintext, "iban");

        assertThat(sealed.keyId()).isEqualTo("k1");
        assertThat(new String(sealed.envelope(), StandardCharsets.ISO_8859_1)).doesNotContain("DE89", "0532");
        assertThat(cipher.open(sealed.envelope(), "iban")).isEqualTo(plaintext);
    }

    @Test
    void usesAFreshRandomIvForEveryValue() {
        Set<String> ivs = new HashSet<>();
        Set<String> envelopes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            byte[] envelope = cipher.seal(bytes("same"), "iban").envelope();
            // 'O' 'V' 'E' version kidLength kid(2) then 12 IV bytes
            ivs.add(Arrays.toString(Arrays.copyOfRange(envelope, 7, 19)));
            envelopes.add(Arrays.toString(envelope));
        }
        assertThat(ivs).hasSize(200);
        assertThat(envelopes).hasSize(200);
    }

    @Test
    void envelopeCarriesVersionAndKeyId() {
        byte[] envelope = cipher.seal(bytes("x"), "n").envelope();

        assertThat(envelope[0]).isEqualTo((byte) 'O');
        assertThat(envelope[3]).isEqualTo((byte) 1);
        assertThat(EnvelopeCipher.keyIdOf(envelope)).isEqualTo("k1");
        assertThat(EnvelopeCipher.textMarker("k1")).isEqualTo("$operaton-ve$1$k1");
    }

    @Test
    void everySingleModifiedByteIsDetected() {
        byte[] envelope = cipher.seal(bytes("secret value"), "iban").envelope();

        IntStream.range(0, envelope.length).forEach(position -> {
            byte[] tampered = envelope.clone();
            tampered[position] ^= 0x01;
            assertThatThrownBy(() -> cipher.open(tampered, "iban"))
                    .as("flipped bit in byte %d", position)
                    .isInstanceOf(VariableEncryptionException.class);
        });
    }

    @Test
    void truncatedAndExtendedDataIsDetected() {
        byte[] envelope = cipher.seal(bytes("secret value"), "iban").envelope();

        assertThatThrownBy(() -> cipher.open(Arrays.copyOf(envelope, envelope.length - 1), "iban"))
                .isInstanceOf(VariableEncryptionException.class);
        assertThatThrownBy(() -> cipher.open(Arrays.copyOf(envelope, envelope.length + 1), "iban"))
                .isInstanceOf(VariableEncryptionException.class);
        assertThatThrownBy(() -> cipher.open(new byte[0], "iban"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("iban");
        assertThatThrownBy(() -> cipher.open(null, "iban")).isInstanceOf(VariableEncryptionException.class);
    }

    @Test
    void aCiphertextCannotBeMovedToAnotherVariable() {
        byte[] envelope = cipher.seal(bytes("DE89"), "iban").envelope();

        assertThatThrownBy(() -> cipher.open(envelope, "otherName"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Integrity check failed")
                .hasMessageContaining("otherName");
    }

    @Test
    void decryptsWithAnyKnownKeyIdAndFailsForUnknownOnes() {
        byte[] oldEnvelope = cipher.seal(bytes("old"), "v").envelope();
        EnvelopeCipher rotated = new EnvelopeCipher(new StaticKeyProvider("k2", Map.of("k1", K1, "k2", K2)));
        EnvelopeCipher onlyNewKey = new EnvelopeCipher(new StaticKeyProvider("k2", Map.of("k2", K2)));

        assertThat(rotated.open(oldEnvelope, "v")).isEqualTo(bytes("old"));
        assertThat(rotated.seal(bytes("new"), "v").keyId()).isEqualTo("k2");
        assertThatThrownBy(() -> onlyNewKey.open(oldEnvelope, "v"))
                .isInstanceOf(UnknownKeyException.class)
                .hasMessageContaining("'k1'")
                .hasMessageContaining("'v'");
    }

    @Test
    void theWrongKeyUnderTheSameIdFailsTheIntegrityCheck() {
        byte[] envelope = cipher.seal(bytes("secret"), "v").envelope();
        EnvelopeCipher other = new EnvelopeCipher(new StaticKeyProvider("k1", Map.of("k1", K2)));

        assertThatThrownBy(() -> other.open(envelope, "v"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("Integrity check failed");
    }

    @Test
    void anUnsupportedVersionIsReportedAsSuch() {
        byte[] envelope = cipher.seal(bytes("secret"), "v").envelope();
        envelope[3] = 2;

        assertThatThrownBy(() -> cipher.open(envelope, "v"))
                .isInstanceOf(VariableEncryptionException.class)
                .hasMessageContaining("version 2");
    }

    @Test
    void exceptionMessagesNeverContainValuesOrKeys() {
        byte[] envelope = cipher.seal(bytes("TOPSECRETVALUE"), "iban").envelope();
        envelope[envelope.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.open(envelope, "iban"))
                .satisfies(e -> assertThat(e.toString() + e.getCause())
                        .doesNotContain("TOPSECRETVALUE", K1, "0123456789abcdef"));
    }
}
