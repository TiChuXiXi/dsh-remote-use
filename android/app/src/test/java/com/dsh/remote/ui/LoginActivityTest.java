package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;

import com.dsh.remote.net.Json;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.List;
import java.util.Map;

/**
 * Drives the launcher Activity through the real Android lifecycle and real click dispatch.
 *
 * <p>Every test here goes through {@code Robolectric.buildActivity(...).setup()} or an
 * explicit lifecycle chain, and every user action goes through {@code View.performClick()}.
 * No Activity method is called directly.
 *
 * <p>The network is a loopback {@link StubGateway}; the real {@code net/} stack runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// conscrypt-openjdk-uber ships no linux-aarch_64 native library, and Robolectric
// installs it as a JCE provider during setUpApplicationState. Nothing here uses TLS
// to a real peer, so switch it off rather than fail on UnsatisfiedLinkError.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LoginActivityTest extends UiTestBase {

    private StubGateway gw;

    @Before
    public void setUp() throws Exception {
        gw = newStub();
        prefs().edit().clear().commit();
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.close();
        }
    }

    // ------------------------------------------------------------------ rendering

    @Test
    public void rendersTheLoginForm() {
        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        assertNotNull("content view was never set", activity.getWindow().getDecorView());

        List<EditText> inputs = editTexts(activity);
        assertEquals("expected three EditTexts (url, username, password)", 3, inputs.size());
        assertNotNull("server URL field missing", editWithHint(activity, "https://gw.example.com"));
        assertNotNull("username field missing", editWithHint(activity, "username"));
        assertNotNull("password field missing", editWithHint(activity, "password"));

        Button login = findButton(activity, "Login");
        assertNotNull("Login button missing", login);
        assertTrue("Login button should start enabled", login.isEnabled());
        assertTrue("Login button has no click listener", login.hasOnClickListeners());

        assertNotNull("title TextView missing", findTextView(activity, "DSH Remote"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ empty submit

    /**
     * The single most valuable test in this file: it taps a real button with empty fields.
     * Before this test existed, a NullPointerException inside that onClick would only have
     * been discovered by a human with a phone.
     */
    @Test
    public void clickingLoginWithEmptyFieldsDoesNotCrashAndIssuesNoRequest() {
        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        Button login = findButton(activity, "Login");
        assertNotNull(login);
        assertTrue("performClick() reported no click listener", login.performClick());

        idle();

        assertTrue("expected the required-fields message, tree text was:\n" + allText(activity),
                allText(activity).contains("Server URL and username are required"));
        assertTrue("the button should not be left disabled", login.isEnabled());
        assertEquals("an empty form must not touch the network", 0, gw.requestCount());

        // still alive after the click
        assertFalse(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void clickingLoginWithUrlButNoUsernameIssuesNoRequest() {
        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        editWithHint(activity, "https://gw.example.com").setText(gw.baseUrl());
        findButton(activity, "Login").performClick();
        idle();

        assertTrue(allText(activity).contains("Server URL and username are required"));
        assertEquals(0, gw.requestCount());
        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ failed login

    @Test
    public void failedLoginShowsAnErrorInsteadOfCrashing() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/gateway/login")) {
                return StubGateway.Response.status(401, "invalid_credentials");
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        editWithHint(activity, "https://gw.example.com").setText(gw.baseUrl());
        editWithHint(activity, "username").setText("admin");
        editWithHint(activity, "password").setText("wrong-password");

        Button login = findButton(activity, "Login");
        login.performClick();

        assertTrue("no /gateway/login request reached the stub", waitForRequests(gw, 1));
        assertTrue("expected a 'Login failed' status line, tree text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Login failed"), 20_000L, "Login failed"));

        String text = allText(activity);
        assertTrue("the error should name the cause, got:\n" + text,
                text.contains("invalid_credentials") || text.contains("401"));
        assertTrue("the button must be usable again after a failure", login.isEnabled());
        assertFalse("a failed login must not finish the Activity", activity.isFinishing());
        assertEquals("no token may be stored after a failed login", "", App.getToken(appContext()));

        controller.pause().stop().destroy();
    }

    @Test
    public void unreachableServerIsReportedNotThrown() throws Exception {
        // Bind an ephemeral port, then close it: connecting to it is a guaranteed refusal.
        java.net.ServerSocket dead = new java.net.ServerSocket(0);
        int deadPort = dead.getLocalPort();
        dead.close();

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        editWithHint(activity, "https://gw.example.com").setText("http://127.0.0.1:" + deadPort);
        editWithHint(activity, "username").setText("admin");
        editWithHint(activity, "password").setText("x");
        findButton(activity, "Login").performClick();

        assertTrue("expected a 'Login failed' status line, tree text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Login failed"), 20_000L, "Login failed"));
        assertFalse(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ successful login

    @Test
    public void successfulLoginStoresTheTokenAndGoesToTheSessionList() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/gateway/login")) {
                Map<String, Object> body = Json.obj();
                body.put("ok", true);
                body.put("token", "tok-from-stub");
                body.put("username", "admin");
                body.put("expiresAt", System.currentTimeMillis() + 3_600_000L);
                return StubGateway.Response.json(body);
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        editWithHint(activity, "https://gw.example.com").setText(gw.baseUrl());
        editWithHint(activity, "username").setText("admin");
        editWithHint(activity, "password").setText("devpassword");
        findButton(activity, "Login").performClick();

        assertTrue("no /gateway/login request reached the stub", waitForRequests(gw, 1));
        assertTrue("LoginActivity never navigated away",
                waitFor(() -> activity.isFinishing(), 20_000L, "activity finished"));

        // the request really carried the typed credentials
        Object sent = gw.request(0).bodyJson();
        assertEquals("admin", Json.str(sent, "username"));
        assertEquals("devpassword", Json.str(sent, "password"));

        // and the real store was written by the real code path
        assertEquals("tok-from-stub", App.getToken(appContext()));
        assertEquals("admin", App.getUsername(appContext()));
        assertEquals(gw.baseUrl(), App.getBaseUrl(appContext()));

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("no Activity was started after a successful login", next);
        assertEquals(SessionListActivity.class.getName(), next.getComponent().getClassName());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ stored session

    @Test
    public void validStoredSessionSkipsLogin() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/gateway/session")) {
                return StubGateway.Response.json(singleEntry("ok", Boolean.TRUE));
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "stored-token");

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        assertTrue("no /gateway/session probe reached the stub", waitForRequests(gw, 1));
        assertEquals("the stored bearer must be used for the probe",
                "Bearer stored-token", gw.request(0).header("Authorization"));
        assertTrue("a valid stored session should navigate away",
                waitFor(() -> activity.isFinishing(), 20_000L, "activity finished"));

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(SessionListActivity.class.getName(), next.getComponent().getClassName());

        controller.pause().stop().destroy();
    }

    @Test
    public void expiredStoredSessionFallsBackToTheForm() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/gateway/session")) {
                return StubGateway.Response.status(401, "unauthorized");
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "stale-token");

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        assertTrue(waitForRequests(gw, 1));
        assertTrue("expected the expired-session message, tree text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Stored session expired"),
                        20_000L, "expired message"));

        assertFalse("an expired stored session must not finish the Activity",
                activity.isFinishing());
        assertNotNull("the form must still be there", findButton(activity, "Login"));

        controller.pause().stop().destroy();
    }

    @Test
    public void storedBaseUrlIsPrefilledIntoTheForm() {
        storeLogin("http://example.invalid:9", "alice", "t");
        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).setup();
        LoginActivity activity = controller.get();

        assertEquals("http://example.invalid:9",
                editWithHint(activity, "https://gw.example.com").getText().toString());
        assertEquals("alice", editWithHint(activity, "username").getText().toString());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void fullLifecycleChainDoesNotThrow() {
        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class);
        Activity a = controller.get();

        controller.create();
        controller.start();
        controller.resume();
        controller.pause();
        controller.stop();

        assertNotNull("view tree vanished after stop()", a.getWindow().getDecorView());

        controller.restart();
        controller.resume();
        controller.pause();
        controller.stop();
        controller.destroy();

        // ActivityController.destroy() runs onDestroy() but does not set the finishing flag,
        // so assert on what is observable: the Activity is torn down and did no I/O.
        assertFalse("the Activity should no longer be resumed", a.hasWindowFocus());
        assertNotNull(a.getWindow().getDecorView());
        assertEquals("destroy() must not touch the network", 0, gw.requestCount());
    }

    private static Map<String, Object> singleEntry(String key, Object value) {
        Map<String, Object> m = Json.obj();
        m.put(key, value);
        return m;
    }
}
