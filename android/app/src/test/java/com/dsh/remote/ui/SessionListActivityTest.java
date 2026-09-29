package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;

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
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives {@link SessionListActivity} through the real lifecycle with a stub gateway on the
 * loopback interface. The list rows, the "New session" dialog and the logout path are all
 * exercised by dispatching real clicks / real {@code onItemClick} callbacks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// conscrypt-openjdk-uber ships no linux-aarch_64 native library, and Robolectric
// installs it as a JCE provider during setUpApplicationState. Nothing here uses TLS
// to a real peer, so switch it off rather than fail on UnsatisfiedLinkError.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SessionListActivityTest extends UiTestBase {

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
        ShadowAlertDialog.reset();
    }

    // ------------------------------------------------------------------ rendering

    @Test
    public void rendersTheSessionListFromSessionListRpc() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();

        assertNotNull("Refresh button missing", findButton(activity, "Refresh"));
        assertNotNull("New session button missing", findButton(activity, "New session"));
        assertNotNull("Files button missing", findButton(activity, "Files"));
        assertNotNull("Logout button missing", findButton(activity, "Logout"));
        assertNotNull("ListView missing", findListView(activity));

        assertTrue("session/list was never called", waitForRequests(gw, 1));
        assertEquals("session/list", StubGateway.rpcMethod(gw.request(0)));
        assertEquals("the stored bearer must be sent",
                "Bearer tok", gw.request(0).header("Authorization"));

        assertTrue("the row count never reached the status line, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("2 session(s)"),
                        20_000L, "2 session(s)"));

        ListView list = findListView(activity);
        assertEquals("the adapter did not receive both sessions", 2, list.getAdapter().getCount());

        // render the rows for real: measure + layout, then read what the adapter produced
        List<String> rows = renderedRowText(list);
        assertEquals("expected two rendered rows", 2, rows.size());
        assertTrue("row 1 should show the short session id, got: " + rows,
                rows.get(0).contains("sess-001"));
        assertTrue("row 1 should show its cwd, got: " + rows, rows.get(0).contains("/work/alpha"));
        assertTrue("row 1 should show the running marker, got: " + rows,
                rows.get(0).contains("running"));
        assertTrue("row 2 should show its cwd, got: " + rows, rows.get(1).contains("/work/beta"));
        assertFalse("row 2 is not running, got: " + rows, rows.get(1).contains("running"));

        controller.pause().stop().destroy();
    }

    @Test
    public void refreshFailureIsReportedNotThrown() {
        gw.setHandler(ex -> StubGateway.Response.status(500, "internal_error"));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();

        assertTrue(waitForRequests(gw, 1));
        assertTrue("expected a 'Refresh failed' status line, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Refresh failed"), 20_000L, "failure"));
        assertFalse(activity.isFinishing());

        controller.pause().stop().destroy();
    }

    @Test
    public void refreshButtonRefetches() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));

        findButton(activity, "Refresh").performClick();
        assertTrue("the Refresh button did not trigger a second session/list",
                waitForRequests(gw, 2));
        assertEquals("session/list", StubGateway.rpcMethod(gw.request(1)));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ navigation

    @Test
    public void tappingARowOpensTheChatForThatSession() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("2 session(s)"), 20_000L, "rows"));

        ListView list = findListView(activity);
        assertTrue("onItemClick was not dispatched", list.performItemClick(
                list.getAdapter().getView(0, null, list), 0, 0));

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("tapping a row started no Activity", next);
        assertEquals(ChatActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("the wrong session id was passed",
                "sess-001-aaaa-bbbb", next.getStringExtra(SessionListActivity.EXTRA_SESSION_ID));

        controller.pause().stop().destroy();
    }

    @Test
    public void filesButtonOpensFilesActivity() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();

        findButton(activity, "Files").performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("the Files button started no Activity", next);
        assertEquals(FilesActivity.class.getName(), next.getComponent().getClassName());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ new session dialog

    @Test
    public void newSessionDialogCreatesASessionAndOpensItsChat() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));

        findButton(activity, "New session").performClick();
        idle();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the New session dialog never appeared", dialog);
        assertTrue("the dialog should be showing", dialog.isShowing());

        EditText cwd = editWithHint(dialog.getWindow().getDecorView(), "working directory (empty = omit)");
        assertNotNull("the cwd input is missing from the dialog", cwd);
        cwd.setText("/work/gamma");

        Button create = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertNotNull(create);
        assertEquals("Create", create.getText().toString());
        create.performClick();

        assertTrue("session/create was never called", waitForRequests(gw, 2));
        assertEquals("session/create", StubGateway.rpcMethod(gw.request(1)));
        assertEquals("the typed cwd must reach the RPC", "/work/gamma",
                Json.str(StubGateway.rpcArgs(gw.request(1)), "request", "cwd"));

        // getNextStartedActivity() POPS the queue, so it must be called exactly once:
        // capture it inside the wait condition rather than probing and then reading again.
        final Intent[] captured = new Intent[1];
        assertTrue("the created session's chat never opened",
                waitFor(() -> {
                    if (captured[0] != null) {
                        return true;
                    }
                    captured[0] = Shadows.shadowOf(activity).getNextStartedActivity();
                    return captured[0] != null;
                }, 20_000L, "ChatActivity"));
        Intent next = captured[0];
        assertEquals(ChatActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("sess-new-0001", next.getStringExtra(SessionListActivity.EXTRA_SESSION_ID));

        controller.pause().stop().destroy();
    }

    @Test
    public void newSessionDialogFailureIsReported() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/session/list")) {
                return StubGateway.rpcOk(req, listValue(items(session("sess-1", "/w", false))));
            }
            if (req.path.startsWith("/api/session/create")) {
                return StubGateway.rpcError(req, "session/create-failed", "cwd does not exist");
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));

        findButton(activity, "New session").performClick();
        idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

        assertTrue(waitForRequests(gw, 2));
        assertTrue("expected a 'Create failed' status line, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Create failed"), 20_000L, "failure"));
        assertTrue("the cause should be visible",
                allText(activity).contains("cwd does not exist"));
        assertFalse(activity.isFinishing());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ logout

    @Test
    public void logoutClearsTheStoredLoginAndReturnsToLogin() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/session/list")) {
                return StubGateway.rpcOk(req, listValue(items(session("sess-1", "/w", false))));
            }
            if (req.path.equals("/gateway/logout")) {
                return StubGateway.Response.json(singleEntry("ok", Boolean.TRUE));
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));

        Button logout = findButton(activity, "Logout");
        logout.performClick();
        assertFalse("the Logout button should be disabled while logging out", logout.isEnabled());

        assertTrue("the stored login was never cleared",
                waitFor(() -> App.getToken(appContext()).isEmpty(), 20_000L, "cleared prefs"));
        assertTrue("the Activity never finished", waitFor(() -> activity.isFinishing(),
                20_000L, "finishing"));

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("logout started no Activity", next);
        assertEquals(LoginActivity.class.getName(), next.getComponent().getClassName());

        controller.pause().stop().destroy();
    }

    @Test
    public void logoutStillWorksWhenTheGatewayIsDown() throws Exception {
        java.net.ServerSocket dead = new java.net.ServerSocket(0);
        int deadPort = dead.getLocalPort();
        dead.close();
        storeLogin("http://127.0.0.1:" + deadPort, "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class).setup();
        SessionListActivity activity = controller.get();

        findButton(activity, "Logout").performClick();

        assertTrue("a failed remote logout must still clear local state",
                waitFor(() -> App.getToken(appContext()).isEmpty(), 20_000L, "cleared prefs"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void fullLifecycleChainDoesNotThrow() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<SessionListActivity> controller =
                Robolectric.buildActivity(SessionListActivity.class);
        Activity a = controller.get();
        controller.create();
        controller.start();
        controller.resume();
        assertTrue("onResume should trigger session/list", waitForRequests(gw, 1));
        controller.pause();
        controller.stop();
        controller.restart();
        controller.resume();
        controller.pause();
        controller.stop();
        controller.destroy();
        // ActivityController.destroy() runs onDestroy() but does not set the finishing flag.
        assertFalse("the Activity should no longer be resumed", a.hasWindowFocus());
        assertNotNull(a.getWindow().getDecorView());
    }

    // ------------------------------------------------------------------ helpers

    private StubGateway.Response defaultRouter(StubGateway.Exchange ex) {
        StubGateway.Request req = ex.req();
        if (req.path.startsWith("/api/session/list")) {
            return StubGateway.rpcOk(req, listValue(items(
                    session("sess-001-aaaa-bbbb", "/work/alpha", true),
                    session("sess-002-cccc-dddd", "/work/beta", false))));
        }
        if (req.path.startsWith("/api/session/create")) {
            // sessionCreate() reads value.sessionId, so the value must be an OBJECT.
            Map<String, Object> created = Json.obj();
            created.put("sessionId", "sess-new-0001");
            return StubGateway.rpcOk(req, created);
        }
        if (req.path.equals("/gateway/logout")) {
            return StubGateway.Response.json(singleEntry("ok", Boolean.TRUE));
        }
        return StubGateway.Response.status(404, "unexpected " + req.target);
    }

    private static Map<String, Object> session(String id, String cwd, boolean running) {
        Map<String, Object> m = Json.obj();
        m.put("sessionId", id);
        m.put("cwd", cwd);
        m.put("running", running);
        m.put("updatedAt", System.currentTimeMillis() - 5000L);
        return m;
    }

    private static List<Object> items(Map<String, Object>... entries) {
        List<Object> out = new ArrayList<Object>();
        for (Map<String, Object> e : entries) {
            out.add(e);
        }
        return out;
    }

    private static Map<String, Object> listValue(List<Object> items) {
        Map<String, Object> m = Json.obj();
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> singleEntry(String k, Object v) {
        Map<String, Object> m = Json.obj();
        m.put(k, v);
        return m;
    }

    private static ListView findListView(Activity activity) {
        List<ListView> found = new ArrayList<ListView>();
        collectViews(activity.getWindow().getDecorView(), ListView.class, found);
        assertFalse("no ListView in the Activity", found.isEmpty());
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collectViews(View root, Class<T> type, List<T> out) {
        if (root == null) {
            return;
        }
        if (type.isInstance(root)) {
            out.add((T) root);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectViews(g.getChildAt(i), type, out);
            }
        }
    }

    /** Forces a real measure+layout pass, then reads back the text of each rendered row. */
    private static List<String> renderedRowText(ListView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
        BaseAdapter adapter = (BaseAdapter) list.getAdapter();
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < adapter.getCount(); i++) {
            View row = adapter.getView(i, null, list);
            row.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            assertTrue("row " + i + " produced no height", row.getMeasuredHeight() > 0);
            rows.add(textOf(row));
        }
        return rows;
    }

    private static String textOf(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof android.widget.TextView) {
            sb.append(((android.widget.TextView) v).getText());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(' ').append(textOf(g.getChildAt(i)));
            }
        }
        return sb.toString();
    }
}
