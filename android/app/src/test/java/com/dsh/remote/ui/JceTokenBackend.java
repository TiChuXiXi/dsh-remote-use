package com.dsh.remote.ui;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * A JVM AES/GCM {@link TokenCipher.Backend} used by the token-store tests.
 *
 * <p><b>Why this exists.</b> Robolectric has no {@code AndroidKeyStore} provider (it is a device
 * service backed by {@code libjavacrypto}), so on this host {@code KeyStore.getInstance(
 * "AndroidKeyStore")} throws {@code KeyStoreException: AndroidKeyStore not found}. Without a
 * substitute, the <em>encrypted</em> path could not be executed at all — only the fallback.
 *
 * <p><b>What is and is not substituted.</b> Only the key provider. This backend produces the
 * very same envelope the AndroidKeyStore backend produces (12-byte IV ‖ ciphertext ‖ 16-byte
 * tag), so {@link TokenCipher}'s version prefix, base64 envelope, {@link App}'s storage keys,
 * migration, verification and fallback logic are all the real production code.
 * {@code TokenStoreTest.realAndroidKeyStoreIsUnavailableHereSoTheRealFallbackRuns} exercises
 * the genuine, unsubstituted production backend and asserts the documented fallback.
 *
 * <p>The {@code decryptFailing} variant models a ROM whose Keystore encrypts but cannot
 * decrypt — the case that must never be allowed to destroy a stored session.
 */
final class JceTokenBackend implements TokenCipher.Backend {

    private final SecretKey key;
    private final String failure;
    private final boolean decryptOnly;

    private JceTokenBackend(SecretKey key, String failure, boolean decryptOnly) {
        this.key = key;
        this.failure = failure;
        this.decryptOnly = decryptOnly;
    }

    /** A working AES-256/GCM provider. */
    static JceTokenBackend working() {
        return new JceTokenBackend(newKey(), null, false);
    }

    /** Every operation fails, as on a ROM with a missing or broken Keystore. */
    static JceTokenBackend alwaysFailing(String message) {
        return new JceTokenBackend(newKey(), message, false);
    }

    /** Encryption works, decryption throws: the "encrypts but cannot read it back" ROM. */
    static JceTokenBackend decryptFailing(String message) {
        return new JceTokenBackend(newKey(), message, true);
    }

    private static SecretKey newKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no JCE AES on this JVM", e);
        }
    }

    @Override
    public boolean available() {
        return failure == null;
    }

    @Override
    public byte[] encrypt(byte[] plain) throws Exception {
        if (failure != null && !decryptOnly) {
            throw new GeneralSecurityException(failure);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] body = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(body, 0, out, iv.length, body.length);
        return out;
    }

    @Override
    public byte[] decrypt(byte[] blob) throws Exception {
        if (failure != null) {
            throw new GeneralSecurityException(failure);
        }
        if (blob.length <= 12) {
            throw new GeneralSecurityException("blob too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(blob, 0, 12)));
        return cipher.doFinal(blob, 12, blob.length - 12);
    }
}
