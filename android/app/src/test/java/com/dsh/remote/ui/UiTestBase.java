package com.dsh.remote.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the UI tests.
 *
 * <p>Two things make these tests able to drive the real Activities:
 *
 * <ol>
 *   <li>The Activities build their network client internally from {@link App}'s stored base
 *       URL, so the test writes a base URL pointing at a {@link StubGateway} into
 *       {@code SharedPreferences}. No production seam is introduced and no production class
 *       is mocked.</li>
 *   <li>The Activities do their I/O on a real {@code ExecutorService} thread and marshal the
 *       result back with {@code runOnUiThread}. Robolectric's main looper is PAUSED, so the
 *       tests pump it while waiting for a condition — see {@link #waitFor}.</li>
 * </ol>
 */
abstract class UiTestBase {

    /** Generous: the first Robolectric test in a JVM pays for sandbox + android-all loading. */
    private static final long DEFAULT_TIMEOUT_MS = 20_000L;

    protected Context appContext() {
        return ApplicationProvider.getApplicationContext();
    }

    // ------------------------------------------------------------------ prefs

    protected SharedPreferences prefs() {
        return appContext().getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Puts the app into its "logged in" state so an Activity takes its normal path. */
    protected void storeLogin(String baseUrl, String user, String token) {
        App.saveLogin(appContext(), baseUrl, user, token, System.currentTimeMillis() + 3_600_000L);
    }

    // ------------------------------------------------------------------ looper

    /** Runs one round of pending main-thread work on this (the Robolectric main) thread. */
    protected void idle() {
        ShadowLooper.shadowMainLooper().idle();
    }

    protected void idle(long ms) {
        ShadowLooper.shadowMainLooper().idleFor(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** {@code true} once {@code c} holds; pumps the main looper between checks. */
    protected boolean waitFor(Condition c) {
        return waitFor(c, DEFAULT_TIMEOUT_MS, null);
    }

    protected boolean waitFor(Condition c, long timeoutMs, String what) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            idle();
            if (c.holds()) {
                return true;
            }
            sleep(10);
        }
        idle();
        if (c.holds()) {
            return true;
        }
        if (what != null) {
            System.out.println("[UiTestBase] timed out waiting for: " + what);
        }
        return false;
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    interface Condition {
        boolean holds();
    }

    /** Waits until the gateway has received at least {@code n} requests. */
    protected boolean waitForRequests(StubGateway gw, int n) {
        return waitFor(() -> gw.requestCount() >= n, DEFAULT_TIMEOUT_MS,
                n + " request(s), saw " + gw.requestCount());
    }

    // ------------------------------------------------------------------ view helpers

    /**
     * Depth-first search of the Activity's content view for a {@link Button} whose text
     * matches. The Activities build their views in code, so a test that has to reach a
     * control must find it by what the user sees — which is also what makes the test break
     * if a label is accidentally changed.
     */
    protected static Button findButton(Activity activity, String text) {
        View found = findByText(activity.getWindow().getDecorView(), Button.class, text);
        return (Button) found;
    }

    protected static TextView findTextView(Activity activity, String text) {
        View found = findByText(activity.getWindow().getDecorView(), TextView.class, text);
        return (TextView) found;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> View findByText(View root, Class<T> type, String text) {
        if (root == null) {
            return null;
        }
        if (type.isInstance(root) && root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && text.contentEquals(t)) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), type, text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** All {@link EditText}s under the Activity's decor view, in view-hierarchy order. */
    protected static List<EditText> editTexts(Activity activity) {
        List<EditText> out = new ArrayList<EditText>();
        collect(activity.getWindow().getDecorView(), EditText.class, out);
        return out;
    }

    protected static List<TextView> textViews(Activity activity) {
        List<TextView> out = new ArrayList<TextView>();
        collect(activity.getWindow().getDecorView(), TextView.class, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collect(View root, Class<T> type, List<T> out) {
        if (root == null) {
            return;
        }
        if (type.isInstance(root)) {
            out.add((T) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), type, out);
            }
        }
    }

    /**
     * Every {@link TextView} in the tree whose text contains {@code needle}. Used to assert
     * on status lines and the transcript without pinning down an exact full string.
     */
    protected static List<TextView> textViewsContaining(Activity activity, String needle) {
        List<TextView> out = new ArrayList<TextView>();
        for (TextView tv : textViews(activity)) {
            CharSequence t = tv.getText();
            if (t != null && t.toString().contains(needle)) {
                out.add(tv);
            }
        }
        return out;
    }

    protected static String allText(Activity activity) {
        StringBuilder sb = new StringBuilder();
        for (TextView tv : textViews(activity)) {
            CharSequence t = tv.getText();
            if (t != null && t.length() > 0) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }

    /** The single EditText matching a hint anywhere under the Activity, or null. */
    protected static EditText editWithHint(Activity activity, String hint) {
        return editWithHint(activity.getWindow().getDecorView(), hint);
    }

    /** The single EditText matching a hint under an arbitrary view tree (e.g. a dialog). */
    protected static EditText editWithHint(View root, String hint) {
        List<EditText> out = new ArrayList<EditText>();
        collect(root, EditText.class, out);
        for (EditText e : out) {
            if (e.getHint() != null && hint.contentEquals(e.getHint())) {
                return e;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ misc

    protected static StubGateway newStub() {
        try {
            return new StubGateway();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start the stub gateway", e);
        }
    }

    /** Runs the given runnable on the Robolectric main looper, then drains it. */
    protected void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            idle();
        } else {
            new android.os.Handler(Looper.getMainLooper()).post(r);
            idle();
        }
    }
}
