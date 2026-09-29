package com.dsh.remote.ui;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES/GCM encryption of the gateway bearer token with a key held by the <b>AndroidKeyStore</b>.
 *
 * <p>Key: alias {@value #KEY_ALIAS}, AES-256, GCM, no padding, {@code setUserAuthenticationRequired(false)}
 * (the app must be able to read the token without the screen being unlocked), random IV per
 * encryption (which the Keystore enforces), generated on first use.
 *
 * <p>Stored blob: {@code "v1:" + base64(IV(12 bytes) || ciphertext||tag(16 bytes))} — the same
 * envelope regardless of which backend produced it, so a future key rotation only has to bump
 * the prefix.
 *
 * <p><b>Graceful degradation is the point of this class.</b> The AndroidKeyStore is a device
 * service that is missing on some ROMs and can throw anything at all ({@code KeyStoreException},
 * {@code NoSuchProviderException}, {@code ProviderException}, {@code UnsatisfiedLinkError},
 * {@code NoClassDefFoundError}, …). Every entry point therefore catches {@link Throwable} and
 * reports a {@link CryptoException} instead; {@link App} then keeps using the plain
 * {@code SharedPreferences} value rather than losing the session. Failures are logged and
 * recorded in {@link #lastFallbackReason()} — the fallback is explicit, never silent.
 */
final class TokenCipher {

    static final String TAG = "DshRemote";

    static final String KEYSTORE = "AndroidKeyStore";
    static final String KEY_ALIAS = "dsh_remote_token_v1";
    static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int KEY_BITS = 256;
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    /** Blob version prefix. Anything else is refused (rather than guessed at). */
    static final String BLOB_PREFIX = "v1:";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private TokenCipher() {
    }

    /** Thrown when the Keystore is unavailable or refuses the operation. Never fatal. */
    static final class CryptoException extends Exception {
        CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------ backend

    /**
     * The byte-level crypto. Kept behind an interface for exactly one reason: Robolectric has
     * no AndroidKeyStore provider at all (no device, no {@code libjavacrypto}), so the
     * encrypted path cannot be executed there otherwise. The <em>envelope, storage,
     * migration and fallback</em> logic all lives in this class and is always the real code;
     * only the key provider can be substituted, and only from a test in this package.
     */
    interface Backend {
        boolean available();

        /** @return IV || ciphertext||tag */
        byte[] encrypt(byte[] plain) throws Exception;

        /** @param blob IV || ciphertext||tag */
        byte[] decrypt(byte[] blob) throws Exception;
    }

    private static final Backend KEYSTORE_BACKEND = new Backend() {
        @Override
        public boolean available() {
            try {
                key();
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public byte[] encrypt(byte[] plain) throws Exception {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plain);
            if (iv == null || iv.length == 0) {
                throw new CryptoException("keystore produced no IV", null);
            }
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return out;
        }

        @Override
        public byte[] decrypt(byte[] blob) throws Exception {
            if (blob.length <= IV_BYTES) {
                throw new CryptoException("ciphertext blob is too short", null);
            }
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        }
    };

    private static volatile Backend override;
    private static volatile SecretKey cachedKey;
    private static volatile String lastFallbackReason;
    private static volatile String lastLoggedReason;
    private static volatile boolean knownUnusable;

    /** Test-only: replaces the AndroidKeyStore backend. Never set in production code. */
    static void setBackendForTests(Backend backend) {
        override = backend;
    }

    static void clearBackendForTests() {
        override = null;
    }

    private static Backend backend() {
        Backend o = override;
        return o == null ? KEYSTORE_BACKEND : o;
    }

    /** True when the configured backend can actually produce a key right now. */
    static boolean available() {
        try {
            return backend().available();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Loads (or, on first use, generates) the AES key. The AndroidKeyStore key is created with
     * {@code KeyGenParameterSpec} as described in the class comment; it never leaves the
     * Keystore, so this method returns a handle, not key material.
     */
    private static SecretKey key() throws Exception {
        SecretKey cached = cachedKey;
        if (cached != null) {
            return cached;
        }
        synchronized (TokenCipher.class) {
            if (cachedKey != null) {
                return cachedKey;
            }
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                cachedKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                return cachedKey;
            }
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            cachedKey = generator.generateKey();
            return cachedKey;
        }
    }

    // ------------------------------------------------------------------ envelope

    /** @return {@code "v1:" + base64(iv||ciphertext)}; throws when the Keystore is unusable. */
    static String encrypt(String plain) throws CryptoException {
        String text = plain == null ? "" : plain;
        try {
            byte[] blob = backend().encrypt(text.getBytes(UTF8));
            return BLOB_PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (Throwable t) {
            throw new CryptoException(describe(t), t);
        }
    }

    /** Inverse of {@link #encrypt}; throws when the blob is malformed or cannot be decrypted. */
    static String decrypt(String stored) throws CryptoException {
        if (stored == null || !stored.startsWith(BLOB_PREFIX)) {
            throw new CryptoException("unrecognised token blob", null);
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(stored.substring(BLOB_PREFIX.length()));
        } catch (RuntimeException e) {
            throw new CryptoException("token blob is not valid base64: " + e, e);
        }
        try {
            return new String(backend().decrypt(blob), UTF8);
        } catch (Throwable t) {
            throw new CryptoException(describe(t), t);
        }
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Records (and logs once per distinct reason) that the encrypted store could not be used.
     * Called by {@link App} every time it falls back, so "the token is in plaintext" is always
     * explainable from logcat and from {@link App#tokenStorageMode(android.content.Context)}.
     */
    static void noteFallback(String reason) {
        knownUnusable = true;
        lastFallbackReason = reason;
        if (reason != null && !reason.equals(lastLoggedReason)) {
            lastLoggedReason = reason;
            Log.w(TAG, "token store: falling back to plain SharedPreferences: " + reason);
        }
    }

    /** The most recent fallback reason, or null when the last operation used the Keystore. */
    static String lastFallbackReason() {
        return lastFallbackReason;
    }

    /**
     * True once this process has established that the Keystore cannot be used. Reads then stop
     * retrying the (failing, and on a broken ROM slow) key lookup on every single access; a
     * login still retries, because that is the natural moment for the situation to change.
     */
    static boolean isKnownUnusable() {
        return knownUnusable;
    }

    static void noteSuccess() {
        lastFallbackReason = null;
        knownUnusable = false;
    }

    /** Test-only: forget process-wide state (the cached key and the fallback bookkeeping). */
    static void resetForTests() {
        override = null;
        cachedKey = null;
        lastFallbackReason = null;
        lastLoggedReason = null;
        knownUnusable = false;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown failure";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
    }
}
