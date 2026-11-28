/*
 * Copyright 2026 the Operaton Extensions contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.operaton.extensions.plugin.variableencryption.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.operaton.extensions.plugin.variableencryption.KeyProvider;
import org.operaton.extensions.plugin.variableencryption.UnknownKeyException;
import org.operaton.extensions.plugin.variableencryption.VariableEncryptionException;

/**
 * AES-256-GCM with a random 96-bit IV per value and a self-describing envelope. Not API.
 *
 * <p>Envelope layout (the bytes stored in the byte array of the variable row):
 *
 * <pre>
 * 'O' 'V' 'E'   magic
 * version       1 byte, currently 1
 * kidLength     1 byte (1..64)
 * kid           key id, ASCII
 * iv            12 random bytes
 * ciphertext    the encrypted payload followed by the 16 byte GCM tag
 * </pre>
 *
 * The header (magic, version, key id) and the variable name are authenticated as additional data:
 * changing the key id, the version or moving a ciphertext to a variable with another name makes
 * decryption fail. Thread-safe (a {@link Cipher} is created per call).
 */
public final class EnvelopeCipher {

    /** Prefix of the marker written to the text column of an encrypted row, followed by the key id. */
    public static final String TEXT_PREFIX = "$operaton-ve$1$";

    private static final byte[] MAGIC = {'O', 'V', 'E'};
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_LENGTH = MAGIC.length + 2 + 1 + IV_BYTES + TAG_BITS / 8;

    private final KeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    /**
     * Creates the cipher.
     *
     * @param keys supplies the active key and the keys of existing data
     */
    public EnvelopeCipher(KeyProvider keys) {
        this.keys = keys;
    }

    /** The marker for the text column of a row encrypted with the given key. */
    public static String textMarker(String keyId) {
        return TEXT_PREFIX + keyId;
    }

    /** Whether the text column value marks an encrypted row. */
    public static boolean isMarker(String text) {
        return text != null && text.startsWith(TEXT_PREFIX);
    }

    /** The id of the key that encrypts new values. */
    public String activeKeyId() {
        return keys.activeKeyId();
    }

    /**
     * Encrypts a payload with the active key.
     *
     * @param plaintext the bytes to protect
     * @param variableName the name of the variable, authenticated with the data (may be {@code null})
     * @return the key id and the envelope
     * @throws VariableEncryptionException if the key cannot be obtained or encryption fails
     */
    public Sealed seal(byte[] plaintext, String variableName) {
        String keyId = keys.activeKeyId();
        SecretKey key = Keys.requireAes256(keyId, keys.key(keyId));
        byte[] kid = Keys.requireValidId(keyId).getBytes(StandardCharsets.US_ASCII);
        byte[] header = header(kid);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(header, variableName));
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] envelope = new byte[header.length + IV_BYTES + encrypted.length];
            System.arraycopy(header, 0, envelope, 0, header.length);
            System.arraycopy(iv, 0, envelope, header.length, IV_BYTES);
            System.arraycopy(encrypted, 0, envelope, header.length + IV_BYTES, encrypted.length);
            return new Sealed(keyId, envelope);
        } catch (GeneralSecurityException e) {
            throw new VariableEncryptionException(
                    "Cannot encrypt variable '" + variableName + "' with key '" + keyId + "': " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts an envelope.
     *
     * @param envelope the stored bytes
     * @param variableName the name of the variable the envelope was written for (may be {@code null})
     * @return the payload
     * @throws UnknownKeyException if the key id of the envelope is not known to the key provider
     * @throws VariableEncryptionException if the envelope is malformed, was modified, belongs to another
     *     variable or was encrypted with a different key than the one registered under its key id
     */
    public byte[] open(byte[] envelope, String variableName) {
        if (envelope == null || envelope.length < MIN_LENGTH) {
            throw new VariableEncryptionException("Variable '" + variableName
                    + "' is marked as encrypted but its stored data is missing or truncated (tampered data?)");
        }
        if (envelope[0] != MAGIC[0] || envelope[1] != MAGIC[1] || envelope[2] != MAGIC[2]) {
            throw new VariableEncryptionException("Variable '" + variableName
                    + "' is marked as encrypted but its stored data is not an encryption envelope (tampered data?)");
        }
        int version = envelope[3] & 0xFF;
        if (version != VERSION) {
            throw new VariableEncryptionException("Variable '" + variableName + "' uses envelope version " + version
                    + "; this plugin reads version " + VERSION + " (upgrade the plugin)");
        }
        int kidLength = envelope[4] & 0xFF;
        int headerLength = MAGIC.length + 2 + kidLength;
        if (kidLength == 0 || envelope.length < headerLength + IV_BYTES + TAG_BITS / 8) {
            throw new VariableEncryptionException(
                    "Variable '" + variableName + "' has a malformed encryption envelope (tampered data?)");
        }
        String keyId = new String(envelope, MAGIC.length + 2, kidLength, StandardCharsets.US_ASCII);
        SecretKey key;
        try {
            key = Keys.requireAes256(keyId, keys.key(keyId));
        } catch (UnknownKeyException e) {
            throw new UnknownKeyException(keyId, variableName);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, envelope, headerLength, IV_BYTES));
            cipher.updateAAD(aad(Arrays.copyOf(envelope, headerLength), variableName));
            return cipher.doFinal(envelope, headerLength + IV_BYTES, envelope.length - headerLength - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new VariableEncryptionException(
                    "Integrity check failed for variable '" + variableName + "' (key '" + keyId
                            + "'): the stored data was modified, belongs to another variable, or the key '" + keyId
                            + "' is not the key it was encrypted with",
                    e);
        } catch (GeneralSecurityException e) {
            throw new VariableEncryptionException(
                    "Cannot decrypt variable '" + variableName + "' with key '" + keyId + "': " + e.getMessage(), e);
        }
    }

    /**
     * The key id written in an envelope, without decrypting.
     *
     * @param envelope the stored bytes
     * @return the key id, or {@code null} if the bytes are no envelope
     */
    public static String keyIdOf(byte[] envelope) {
        if (envelope == null
                || envelope.length < MIN_LENGTH
                || envelope[0] != MAGIC[0]
                || envelope[1] != MAGIC[1]
                || envelope[2] != MAGIC[2]) {
            return null;
        }
        int kidLength = envelope[4] & 0xFF;
        if (kidLength == 0 || envelope.length < MAGIC.length + 2 + kidLength) {
            return null;
        }
        return new String(envelope, MAGIC.length + 2, kidLength, StandardCharsets.US_ASCII);
    }

    private static byte[] header(byte[] kid) {
        byte[] header = new byte[MAGIC.length + 2 + kid.length];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = (byte) VERSION;
        header[MAGIC.length + 1] = (byte) kid.length;
        System.arraycopy(kid, 0, header, MAGIC.length + 2, kid.length);
        return header;
    }

    private static byte[] aad(byte[] header, String variableName) {
        byte[] name = (variableName == null ? "" : variableName).getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[header.length + 1 + name.length];
        System.arraycopy(header, 0, aad, 0, header.length);
        aad[header.length] = 0;
        System.arraycopy(name, 0, aad, header.length + 1, name.length);
        return aad;
    }

    /**
     * The result of an encryption.
     *
     * @param keyId the id of the key that was used
     * @param envelope the bytes to store
     */
    public record Sealed(String keyId, byte[] envelope) {}
}
