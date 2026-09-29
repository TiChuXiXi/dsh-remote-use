package com.dsh.remote.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.dsh.remote.net.DshRpcClient;
import com.dsh.remote.net.Http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * App-level constants, the SharedPreferences login store and small shared helpers.
 *
 * <p>The bearer token is stored <b>encrypted</b> with an AndroidKeyStore AES/GCM key
 * ({@link TokenCipher}): the preference {@code token_enc} holds
 * {@code "v1:" + base64(IV||ciphertext)} and the plain {@code token} key is removed. A token
 * written by an older build is migrated on first read — the user is never logged out by the
 * upgrade.
 *
 * <p><b>Documented fallback.</b> On a device/ROM where the Keystore is missing or broken (and
 * under Robolectric, which has no AndroidKeyStore provider at all) encryption is impossible.
 * The store then keeps the token in plain {@code MODE_PRIVATE} SharedPreferences — the
 * behaviour of every build before this one — and says so: {@link TokenCipher#lastFallbackReason()}
 * carries the reason, {@code logcat} gets one warning per distinct reason, and
 * {@link #tokenStorageMode(Context)} reports which of the two is in use. The session is never
 * lost over a crypto failure, and the fallback is never silent.
 */
public final class App {

    public static final String PREFS_NAME = "dsh_remote";

    static final String TAG = "DshRemote";

    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_TOKEN_ENC = "token_enc";
    private static final String KEY_EXPIRES_AT = "expires_at";

    private App() {
    }

    // ------------------------------------------------------------------ store

    public static void saveLogin(Context ctx, String baseUrl, String user, String token, long expiresAt) {
        SharedPreferences.Editor editor = prefs(ctx).edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_USERNAME, user)
                .putLong(KEY_EXPIRES_AT, expiresAt);
        writeToken(editor, token);
        editor.apply();
        Notify.ensureWatching(ctx);
    }

    public static void clearLogin(Context ctx) {
        prefs(ctx).edit().clear().apply();
        Notify.stopWatching(ctx);
    }

    public static String getBaseUrl(Context ctx) {
        return prefs(ctx).getString(KEY_BASE_URL, "");
    }

    public static String getUsername(Context ctx) {
        return prefs(ctx).getString(KEY_USERNAME, "");
    }

    public static String getToken(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String blob = p.getString(KEY_TOKEN_ENC, null);
        if (blob != null && !blob.isEmpty()) {
            try {
                String token = TokenCipher.decrypt(blob);
                TokenCipher.noteSuccess();
                return token;
            } catch (TokenCipher.CryptoException e) {
                // The Keystore went away (key invalidated, ROM updated, restore to a new
                // device). Fall through to the plaintext copy if this install still has one.
                TokenCipher.noteFallback("read: " + e.getMessage());
            }
        }
        String plain = p.getString(KEY_TOKEN, "");
        if (!plain.isEmpty()) {
            migrateToken(ctx, p, plain);
        }
        return plain;
    }

    public static long getExpiresAt(Context ctx) {
        return prefs(ctx).getLong(KEY_EXPIRES_AT, 0L);
    }

    /**
     * Which store the token currently lives in: {@code "keystore"}, {@code "plaintext"} or
     * {@code "empty"}. Exposed so the fallback is inspectable (and testable) rather than a
     * rumour.
     */
    static String tokenStorageMode(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String blob = p.getString(KEY_TOKEN_ENC, null);
        if (blob != null && !blob.isEmpty()) {
            return "keystore";
        }
        return p.getString(KEY_TOKEN, "").isEmpty() ? "empty" : "plaintext";
    }

    /** Encrypts into {@code token_enc}; on any crypto failure stores plaintext instead. */
    private static void writeToken(SharedPreferences.Editor editor, String token) {
        String value = token == null ? "" : token;
        if (value.isEmpty()) {
            editor.remove(KEY_TOKEN_ENC).remove(KEY_TOKEN);
            return;
        }
        try {
            String blob = TokenCipher.encrypt(value);
            verifyRoundTrip(value, blob);
            editor.putString(KEY_TOKEN_ENC, blob).remove(KEY_TOKEN);
            TokenCipher.noteSuccess();
        } catch (TokenCipher.CryptoException e) {
            TokenCipher.noteFallback("write: " + e.getMessage());
            // Explicit, documented fallback: a usable plaintext token beats a lost session.
            editor.remove(KEY_TOKEN_ENC).putString(KEY_TOKEN, value);
        }
    }

    /**
     * Decrypts the freshly written blob before the plaintext copy is dropped. A ROM whose
     * Keystore encrypts but cannot decrypt would otherwise destroy the stored session on the
     * very next read — this is the cheapest possible insurance against that.
     */
    private static void verifyRoundTrip(String value, String blob) throws TokenCipher.CryptoException {
        String back;
        try {
            back = TokenCipher.decrypt(blob);
        } catch (TokenCipher.CryptoException e) {
            throw new TokenCipher.CryptoException(
                    "verify: the Keystore cannot decrypt what it just encrypted (" + e.getMessage() + ")", e);
        }
        if (!value.equals(back)) {
            throw new TokenCipher.CryptoException("verify: the blob did not round-trip", null);
        }
    }

    /**
     * Transparent upgrade of a token stored by an older build: encrypt it, drop the plaintext.
     * If encryption is impossible the plaintext is deliberately <b>left in place</b> so the
     * session survives; the next write (login) tries again.
     */
    private static void migrateToken(Context ctx, SharedPreferences p, String plain) {
        if (TokenCipher.isKnownUnusable()) {
            // Already established this process (see TokenCipher.isKnownUnusable); do not
            // re-attempt — and re-log — the same failing key lookup on every store access.
            return;
        }
        try {
            String blob = TokenCipher.encrypt(plain);
            p.edit().putString(KEY_TOKEN_ENC, blob).remove(KEY_TOKEN).apply();
            TokenCipher.noteSuccess();
            Log.i(TAG, "migrated the stored gateway token to the Keystore-encrypted store");
        } catch (TokenCipher.CryptoException e) {
            TokenCipher.noteFallback("migrate: " + e.getMessage());
        }
    }

    /** Builds an {@link Http} on the stored base URL, applies the stored bearer token and wraps it. */
    public static DshRpcClient rpc(Context ctx) {
        Http http = new Http(getBaseUrl(ctx));
        http.setBearer(getToken(ctx));
        return new DshRpcClient(http);
    }

    // ------------------------------------------------------------ ui helpers

    public static void toast(Context ctx, String msg) {
        Toast.makeText(ctx, msg == null ? "" : msg, Toast.LENGTH_LONG).show();
    }

    public static int dp(Context ctx, float value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Fixed-pool thread factory: all threads are daemons so nothing leaks past Activity death. */
    public static ThreadFactory daemonThreads(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** Casts a parsed JSON object to Map&lt;String,Object&gt;, or null when it is not an object. */
    public static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    private static SharedPreferences prefs(Context ctx) {
        // Registering here means the tracker is in place before the first Activity resumes
        // (every Activity reads the store in onCreate) without editing any Activity.
        Notify.attach(ctx);
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}