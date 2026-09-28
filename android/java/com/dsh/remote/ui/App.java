package com.dsh.remote.ui;

import android.content.Context;
import android.content.SharedPreferences;
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
 * The token is deliberately stored in plain MODE_PRIVATE SharedPreferences: this is a
 * remote-control client, not a credential vault. See the README for the honest note.
 */
public final class App {

    public static final String PREFS_NAME = "dsh_remote";

    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_EXPIRES_AT = "expires_at";

    private App() {
    }

    // ------------------------------------------------------------------ store

    public static void saveLogin(Context ctx, String baseUrl, String user, String token, long expiresAt) {
        prefs(ctx).edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_USERNAME, user)
                .putString(KEY_TOKEN, token)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .apply();
    }

    public static void clearLogin(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    public static String getBaseUrl(Context ctx) {
        return prefs(ctx).getString(KEY_BASE_URL, "");
    }

    public static String getUsername(Context ctx) {
        return prefs(ctx).getString(KEY_USERNAME, "");
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    public static long getExpiresAt(Context ctx) {
        return prefs(ctx).getLong(KEY_EXPIRES_AT, 0L);
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
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}