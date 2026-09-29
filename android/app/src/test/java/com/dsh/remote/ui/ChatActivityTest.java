package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.dsh.remote.net.Json;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives {@link ChatActivity}. This one needs a real WebSocket upgrade: the Activity opens
 * dsh's {@code /api/remote.mux} on a background thread and reports "streams open" only when
 * the RFC 6455 handshake succeeds, so the stub completes a genuine handshake (correct
 * {@code Sec-WebSocket-Accept}) and then pushes real server text frames.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// conscrypt-openjdk-uber ships no linux-aarch_64 native library, and Robolectric
// installs it as a JCE provider during setUpApplicationState. Nothing here uses TLS
// to a real peer, so switch it off rather than fail on UnsatisfiedLinkError.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ChatActivityTest extends UiTestBase {

    private static final String SESSION = "sess-chat-0001-xxxx";

    private StubGateway gw;
    /** Mux frames to push after the handshake; set before the Activity is created. */
    private final List<String> frames = new ArrayList<String>();

    @Before
    public void setUp() throws Exception {
        gw = newStub();
        prefs().edit().clear().commit();
        frames.clear();
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
    public void rendersTheChatUiAndOpensTheMux() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();

        assertNotNull("Send button missing", findButton(activity, "Send"));
        assertNotNull("Cancel turn button missing", findButton(activity, "Cancel turn"));
        assertNotNull("message input missing", editWithHint(activity, "Message"));
        assertNotNull("transcript TextView missing", transcript(activity));

        assertTrue("the mux WebSocket was never opened", waitFor(() -> gw.upgradeCount() >= 1,
                20_000L, "mux upgrade"));
        assertTrue("the Activity never reported its streams open, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("streams open"), 20_000L, "streams open"));

        // both streams were opened over the ONE mux socket
        assertEquals("exactly one mux socket should be opened", 1, gw.upgradeCount());
        assertEquals("the WS handshake must carry the bearer token",
                "Bearer tok", gw.request(0).header("Authorization"));

        controller.pause().stop().destroy();
    }

    @Test
    public void noSessionIdIsReportedNotCrashed() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, new Intent(appContext(), ChatActivity.class));
        controller.setup();
        ChatActivity activity = controller.get();

        assertTrue("expected the 'no session id' status, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("no session id"), 20_000L, "no session id"));
        assertEquals("no socket may be opened without a session id", 0, gw.upgradeCount());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ streaming

    @Test
    public void liveAssistantChunksAreRenderedIncrementally() {
        installMuxHandler();
        // start → chunk → chunk → end, as dsh sends them
        frames.add(StubGateway.muxItem("s1", assistantStart()));
        frames.add(StubGateway.muxItem("s1", StubGateway.assistantChunk("PONG-")));
        frames.add(StubGateway.muxItem("s1", StubGateway.assistantChunk("HARNESS")));
        frames.add(StubGateway.muxItem("s1", assistantEnd()));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();

        assertTrue("live chunks never reached the transcript, text was:\n" + allText(activity),
                waitFor(() -> transcriptText(activity).contains("PONG-HARNESS"),
                        20_000L, "PONG-HARNESS"));

        controller.pause().stop().destroy();
    }

    @Test
    public void chunksBeforeStartAreIgnored() {
        installMuxHandler();
        // a chunk with no preceding "start": the Activity's liveSectionOpen guard must drop it
        frames.add(StubGateway.muxItem("s1", StubGateway.assistantChunk("LEAKED")));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> allText(activity).contains("streams open"), 20_000L, "open"));

        assertFalse("a chunk outside a live section must not be rendered",
                transcriptText(activity).contains("LEAKED"));

        controller.pause().stop().destroy();
    }

    @Test
    public void durableEventsAndSnapshotsAreRendered() {
        installMuxHandler();
        frames.add(StubGateway.muxItem("s1", StubGateway.followEvent("user/message",
                content("hello from the phone"))));
        frames.add(StubGateway.muxItem("s1", StubGateway.followEvent("tool/call",
                toolCall("read_file", "{\"path\":\"README.md\"}"))));
        frames.add(StubGateway.muxItem("s1", StubGateway.followEvent("tool/result",
                content("file contents here"))));
        frames.add(StubGateway.muxItem("s1", StubGateway.followEvent("turn/end",
                turnEnd(3L, "completed"))));
        frames.add(StubGateway.muxItem("s1", snapshot(7)));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();

        assertTrue("user/message never rendered, text was:\n" + allText(activity),
                waitFor(() -> transcriptText(activity).contains("YOU: hello from the phone"),
                        20_000L, "user message"));
        assertTrue(waitFor(() -> transcriptText(activity).contains("TOOL CALL read_file"),
                20_000L, "tool call"));
        assertTrue(waitFor(() -> transcriptText(activity).contains("file contents here"),
                20_000L, "tool result"));
        assertTrue("turn/end never rendered, text was:\n" + allText(activity),
                waitFor(() -> transcriptText(activity).contains("turn 3 ended (completed)"),
                        20_000L, "turn end"));
        assertTrue("snapshot never rendered", waitFor(
                () -> transcriptText(activity).contains("[snapshot: 7 records]"),
                20_000L, "snapshot"));

        controller.pause().stop().destroy();
    }

    @Test
    public void unknownFrameShapesAreIgnoredWithoutCrashing() {
        installMuxHandler();
        frames.add(StubGateway.muxItem("s1", "a bare string, not an object"));
        frames.add(StubGateway.muxItem("s1", Json.obj()));
        frames.add(StubGateway.muxItem("s1", StubGateway.followEvent("some/unknown-event", null)));
        frames.add(StubGateway.muxItem("ev", Json.obj()));
        frames.add(StubGateway.muxItem("s1", StubGateway.assistantChunk("STILL-ALIVE")));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> allText(activity).contains("streams open"), 20_000L, "open"));

        assertFalse("a malformed frame took the Activity down", activity.isFinishing());
        assertNotNull(findButton(activity, "Send"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ approvals

    @Test
    public void approvalRequestPopsADialogAndAllowPostsTheOutcome() {
        installMuxHandler();
        frames.add(StubGateway.muxItem("ev", StubGateway.eventsReady("client-7")));
        frames.add(StubGateway.muxItem("ev",
                StubGateway.approvalWaterfall("evt-42", "write_file", "needs to edit a file")));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();

        assertTrue("the approval dialog never appeared",
                waitFor(() -> ShadowAlertDialog.getLatestAlertDialog() != null
                        && ShadowAlertDialog.getLatestAlertDialog().isShowing(),
                        20_000L, "approval dialog"));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the approval dialog must be on screen", dialog.isShowing());
        assertNotNull("the approval dialog must offer Allow once",
                dialog.getButton(AlertDialog.BUTTON_POSITIVE));
        assertNotNull("the approval dialog must offer Reject",
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE));

        assertTrue("the pending approval was not shown in the transcript, text was:\n"
                        + allText(activity),
                transcriptText(activity).contains("[approval pending: write_file"));

        Button allow = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertNotNull(allow);
        assertEquals("Allow once", allow.getText().toString());
        allow.performClick();

        assertTrue("$events/result was never posted", waitForRequests(gw, 2));
        StubGateway.Request post = gw.requestsFor("/api/$events/result").get(0);
        Map<String, Object> args = StubGateway.rpcArgs(post);
        assertEquals("client-7", Json.str(args, "clientId"));
        assertEquals("evt-42", Json.str(args, "eventId"));
        assertEquals("next", Json.str(args, "outcome", "kind"));
        assertTrue("the answer should be reported, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("approval answered"), 20_000L, "answered"));

        controller.pause().stop().destroy();
    }

    @Test
    public void approvalRejectPostsARejectedOutcome() {
        installMuxHandler();
        frames.add(StubGateway.muxItem("ev", StubGateway.eventsReady("client-9")));
        frames.add(StubGateway.muxItem("ev",
                StubGateway.approvalWaterfall("evt-99", "run_shell", null)));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> ShadowAlertDialog.getLatestAlertDialog() != null, 20_000L, "dialog"));

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        Button reject = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Reject", reject.getText().toString());
        reject.performClick();

        assertTrue(waitForRequests(gw, 2));
        Map<String, Object> args = StubGateway.rpcArgs(gw.requestsFor("/api/$events/result").get(0));
        assertEquals("rejected", Json.str(args, "outcome", "kind"));
        assertEquals("user-rejected", Json.str(args, "outcome", "error", "code"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ actions

    @Test
    public void sendButtonPostsSessionPromptWithTheTypedText() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> allText(activity).contains("streams open"), 20_000L, "open"));

        EditText input = editWithHint(activity, "Message");
        input.setText("hello dsh");
        findButton(activity, "Send").performClick();

        assertTrue("session/prompt was never posted",
                waitFor(() -> !gw.requestsFor("/api/session/prompt").isEmpty(), 20_000L, "prompt"));
        Map<String, Object> args = StubGateway.rpcArgs(gw.requestsFor("/api/session/prompt").get(0));
        assertEquals(SESSION, Json.str(args, "request", "sessionId"));
        assertEquals("hello dsh",
                Json.str(args, "request", "content", "0", "text"));
        assertTrue("the sent text should appear in the transcript immediately",
                transcriptText(activity).contains("YOU: hello dsh"));

        controller.pause().stop().destroy();
    }

    @Test
    public void sendWithEmptyInputPostsNothing() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> allText(activity).contains("streams open"), 20_000L, "open"));

        int before = gw.requestCount();
        findButton(activity, "Send").performClick();
        idle(300);

        assertEquals("an empty message must not be sent", before, gw.requestCount());
        controller.pause().stop().destroy();
    }

    @Test
    public void cancelButtonPostsSessionCancel() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();
        assertTrue(waitFor(() -> allText(activity).contains("streams open"), 20_000L, "open"));

        findButton(activity, "Cancel turn").performClick();

        assertTrue("session/cancel was never posted",
                waitFor(() -> !gw.requestsFor("/api/session/cancel").isEmpty(), 20_000L, "cancel"));
        Map<String, Object> args = StubGateway.rpcArgs(gw.requestsFor("/api/session/cancel").get(0));
        assertEquals(SESSION, Json.str(args, "request", "sessionId"));

        controller.pause().stop().destroy();
    }

    @Test
    public void aDeadServerIsReportedAsAStreamFailure() throws Exception {
        java.net.ServerSocket dead = new java.net.ServerSocket(0);
        int deadPort = dead.getLocalPort();
        dead.close();
        storeLogin("http://127.0.0.1:" + deadPort, "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        ChatActivity activity = controller.get();

        assertTrue("a failed mux open must be reported, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("stream open failed"),
                        20_000L, "stream open failed"));
        assertFalse("a failed stream must not finish the Activity", activity.isFinishing());
        assertNotNull("the UI must still be usable", findButton(activity, "Send"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void fullLifecycleChainDoesNotThrow() {
        installMuxHandler();
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, chatIntent());
        Activity a = controller.get();
        controller.create();
        controller.start();
        controller.resume();
        assertTrue(waitFor(() -> gw.upgradeCount() >= 1, 20_000L, "mux upgrade"));
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

    @Test
    public void destroyingWhileStreamingDoesNotCrash() {
        installMuxHandler();
        frames.add(StubGateway.muxItem("s1", assistantStart()));
        frames.add(StubGateway.muxItem("s1", StubGateway.assistantChunk("late")));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<ChatActivity> controller = chat();
        assertTrue(waitFor(() -> gw.upgradeCount() >= 1, 20_000L, "mux upgrade"));
        controller.pause().stop().destroy();
        idle(500);
        // nothing to assert beyond "no exception escaped the mux reader thread"
        assertFalse("the Activity should no longer be resumed",
                controller.get().hasWindowFocus());
        assertNotNull(controller.get().getWindow().getDecorView());
    }

    // ------------------------------------------------------------------ helpers

    private ActivityController<ChatActivity> chat() {
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, chatIntent());
        controller.setup();
        return controller;
    }

    private Intent chatIntent() {
        return new Intent(appContext(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_SESSION_ID, SESSION);
    }

    /**
     * Installs a handler that upgrades the mux and queues {@link #frames} for delivery once
     * the client has opened both mux streams (see {@link StubGateway#queueFrames}).
     */
    private void installMuxHandler() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/api/remote.mux")) {
                if (!frames.isEmpty()) {
                    gw.queueFrames(ex.sink(), frames.toArray(new String[0]));
                }
                return ex.upgrade();
            }
            if (req.path.startsWith("/api/")) {
                return StubGateway.rpcOk(req, emptyValue());
            }
            if (req.path.equals("/gateway/logout")) {
                return StubGateway.Response.json(Json.obj());
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
    }

    private static View transcript(ChatActivity activity) {
        List<android.widget.TextView> all = textViews(activity);
        for (android.widget.TextView tv : all) {
            if (tv.getTypeface() != null && tv.getTypeface().equals(android.graphics.Typeface.MONOSPACE)) {
                return tv;
            }
        }
        return null;
    }

    private static String transcriptText(ChatActivity activity) {
        View v = transcript(activity);
        return v == null ? "" : ((android.widget.TextView) v).getText().toString();
    }

    private static Map<String, Object> assistantStart() {
        Map<String, Object> frame = Json.obj();
        frame.put("type", "start");
        Map<String, Object> value = Json.obj();
        value.put("type", "assistant-stream");
        value.put("frame", frame);
        return value;
    }

    private static Map<String, Object> assistantEnd() {
        Map<String, Object> frame = Json.obj();
        frame.put("type", "end");
        Map<String, Object> value = Json.obj();
        value.put("type", "assistant-stream");
        value.put("frame", frame);
        return value;
    }

    private static Map<String, Object> snapshot(int records) {
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < records; i++) {
            list.add(Json.obj());
        }
        Map<String, Object> value = Json.obj();
        value.put("type", "snapshot");
        value.put("records", list);
        return value;
    }

    private static Map<String, Object> content(String text) {
        Map<String, Object> part = Json.obj();
        part.put("type", "text");
        part.put("text", text);
        List<Object> parts = new ArrayList<Object>();
        parts.add(part);
        Map<String, Object> message = Json.obj();
        message.put("content", parts);
        Map<String, Object> data = Json.obj();
        data.put("message", message);
        return data;
    }

    private static Map<String, Object> toolCall(String name, String arguments) {
        Map<String, Object> data = Json.obj();
        data.put("name", name);
        data.put("arguments", arguments);
        return data;
    }

    private static Map<String, Object> turnEnd(long turn, String reason) {
        Map<String, Object> data = Json.obj();
        data.put("turn", turn);
        data.put("reason", reason);
        return data;
    }

    private static Map<String, Object> emptyValue() {
        return Json.obj();
    }
}
