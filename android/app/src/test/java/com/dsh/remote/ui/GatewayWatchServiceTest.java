package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import com.dsh.remote.net.Json;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The background watcher: a real {@link GatewayWatchService} driven through
 * {@link ServiceController}, talking to a loopback {@link StubGateway} over the real
 * {@code net/} stack (real socket, real RFC 6455 upgrade, real frames).
 *
 * <p>Frames are pushed on the service's own mux socket with
 * {@link StubGateway.FrameSink#send}, which is how a real host would deliver
 * {@code turn/end} and {@code approval/request}. The pushes are retried until the expected
 * notification appears, because a frame that arrives before the client has registered the
 * stream is (correctly) dropped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class GatewayWatchServiceTest extends UiTestBase {

    private StubGateway gw;
    private final List<StubGateway.FrameSink> sinks =
            Collections.synchronizedList(new ArrayList<StubGateway.FrameSink>());
    /** Sessions the stub reports as running. */
    private final List<String> running = new ArrayList<String>();

    @Before
    public void setUp() throws Exception {
        Notify.resetForTests();
        TokenCipher.resetForTests();
        prefs().edit().clear().commit();
        // Robolectric denies runtime permissions by default. Granting POST_NOTIFICATIONS here
        // is the "user allowed notifications" state; the denied tests revoke it explicitly.
        Shadows.shadowOf((Application) appContext())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        sinks.clear();
        running.clear();
        running.add("sess-001");

        gw = newStub();
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.equals("/api/remote.mux")) {
                StubGateway.FrameSink sink = ex.sink();
                sinks.add(sink);
                return ex.upgrade();
            }
            if (req.path.equals("/api/session/list")) {
                return StubGateway.rpcOk(req, sessionList());
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });

        storeLogin(gw.baseUrl(), "admin", "watch-token");
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.close();
        }
        Notify.resetForTests();
        TokenCipher.resetForTests();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void startsAndStopsCleanlyWithTheOngoingNotification() {
        ServiceController<GatewayWatchService> controller = create();
        GatewayWatchService service = controller.get();
        ShadowService shadow = Shadows.shadowOf(service);

        assertTrue("startForeground() was never called",
                shadow.isLastForegroundNotificationAttached());
        assertEquals("the ongoing notification must use its own id",
                Notify.ONGOING_ID, shadow.getLastForegroundNotificationId());
        assertEquals("the ongoing notification must use the low-importance channel",
                Notify.CHANNEL_SERVICE, shadow.getLastForegroundNotification().getChannelId());

        controller.startCommand(0, 0);
        assertTrue("the service did not report itself as watching", service.isWatching());
        assertTrue("the service never reached the gateway", waitForRequests(gw, 1));
        assertTrue("the service never opened the mux",
                waitFor(() -> gw.upgradeCount() >= 1, 20_000L, "mux upgrade"));

        controller.destroy();
        assertFalse("destroy() must stop watching", service.isWatching());
        assertTrue("the ongoing notification must be removed on destroy",
                shadow.isForegroundStopped());
    }

    @Test
    public void anExplicitStopActionStopsTheService() {
        ServiceController<GatewayWatchService> controller = create();
        controller.startCommand(0, 0);

        controller.withIntent(new Intent(appContext(), GatewayWatchService.class)
                .setAction(GatewayWatchService.ACTION_STOP)).startCommand(0, 0);

        assertTrue("ACTION_STOP must call stopSelf()",
                Shadows.shadowOf(controller.get()).isStoppedBySelf());
        controller.destroy();
    }

    @Test
    public void theServiceStopsItselfWhenThereIsNoToken() {
        ServiceController<GatewayWatchService> controller = create();
        App.clearLogin(appContext()); // logged out while the service was alive

        controller.startCommand(0, 0);

        assertTrue("the service kept polling without a token",
                waitFor(() -> Shadows.shadowOf(controller.get()).isStoppedBySelf(),
                        20_000L, "stopSelf"));
        controller.destroy();
    }

    @Test
    public void aGatewayThatIsDownDoesNotCrashTheService() throws Exception {
        java.net.ServerSocket dead = new java.net.ServerSocket(0);
        int port = dead.getLocalPort();
        dead.close();
        storeLogin("http://127.0.0.1:" + port, "admin", "tok");

        ServiceController<GatewayWatchService> controller = create();
        controller.startCommand(0, 0);

        assertTrue("a failing tick was never recorded",
                waitFor(() -> controller.get().lastError() != null, 20_000L, "lastError"));
        assertTrue(controller.get().isWatching());

        controller.destroy();
        assertFalse(controller.get().isWatching());
    }

    // ------------------------------------------------------------------ events

    @Test
    public void aTurnEndEventFromTheGatewayPostsANotification() throws Exception {
        ServiceController<GatewayWatchService> controller = start();
        GatewayWatchService service = controller.get();
        assertTrue("the service never followed the running session",
                waitFor(() -> service.followedCount() >= 1, 20_000L, "session followed"));

        String frame = StubGateway.muxItem(GatewayWatchService.streamIdFor("sess-001"),
                StubGateway.followEvent("turn/end", turnEnd(3, "completed")));

        assertTrue("no turn/end notification arrived", pushUntil(sink(), frame, 8_000L));

        assertEquals(1, events().size());
        Notification n = events().get(0);
        assertEquals("Turn finished", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Session sess-001 - turn 3 ended (completed)",
                n.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(Notify.CHANNEL_EVENTS, n.getChannelId());

        controller.destroy();
    }

    @Test
    public void anApprovalWaterfallPostsANotificationTappingThroughToTheChat() throws Exception {
        ServiceController<GatewayWatchService> controller = start();
        assertTrue(waitFor(() -> controller.get().followedCount() >= 1, 20_000L, "session followed"));

        String frame = StubGateway.muxItem(GatewayWatchService.EVENTS_STREAM,
                StubGateway.approvalWaterfall("ev-1", "shell", "mock approval"));

        assertTrue("no approval notification arrived", pushUntil(sink(), frame, 8_000L));

        assertEquals(1, events().size());
        Notification n = events().get(0);
        assertEquals("Approval needed", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("Tool shell is waiting for your approval: mock approval",
                n.extras.getString(Notification.EXTRA_TEXT));

        Intent tap = Shadows.shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("tapping an approval notification must open the chat",
                ChatActivity.class.getName(), tap.getComponent().getClassName());
        assertEquals("sess-001", tap.getStringExtra(ChatActivity.EXTRA_SESSION_ID));

        controller.destroy();
    }

    /**
     * End-to-end suppression: with {@code sess-001} on screen, its {@code turn/end} is dropped
     * while the same frame for {@code sess-002} — pushed on the same socket, through the same
     * code path — still notifies. The control push is what makes this meaningful.
     */
    @Test
    public void noNotificationForTheSessionCurrentlyOnScreen() throws Exception {
        running.add("sess-002");
        ServiceController<GatewayWatchService> controller = start();
        assertTrue("both running sessions should be followed",
                waitFor(() -> controller.get().followedCount() >= 2, 20_000L, "2 sessions followed"));

        StubGateway.FrameSink sink = sink();
        ActivityController<ChatActivity> chat = Robolectric.buildActivity(ChatActivity.class,
                new Intent(appContext(), ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_SESSION_ID, "sess-001"))
                .create().start().resume();
        try {
            assertEquals("the resumed ChatActivity's session was not tracked",
                    "sess-001", Notify.visibleSessionId());

            String other = StubGateway.muxItem(GatewayWatchService.streamIdFor("sess-002"),
                    StubGateway.followEvent("turn/end", turnEnd(1, "completed")));
            assertTrue("control: a turn in another session must notify",
                    pushUntil(sink, other, 8_000L));
            assertEquals(1, events().size());

            String onScreen = StubGateway.muxItem(GatewayWatchService.streamIdFor("sess-001"),
                    StubGateway.followEvent("turn/end", turnEnd(1, "completed")));
            pushFor(sink, onScreen, 2_000L);

            assertEquals("the session on screen must not be notified", 1, events().size());
            assertEquals("Session sess-002 - turn 1 ended (completed)",
                    events().get(0).extras.getString(Notification.EXTRA_TEXT));
        } finally {
            chat.pause().stop().destroy();
            controller.destroy();
        }
    }

    @Test
    public void postNotificationsDeniedStillRunsTheServiceWithoutCrashing() {
        Shadows.shadowOf((Application) appContext())
                .denyPermissions("android.permission.POST_NOTIFICATIONS");

        ServiceController<GatewayWatchService> controller = start();
        try {
            assertTrue(controller.get().isWatching());
            assertTrue("the service must still keep the mux open",
                    waitFor(() -> gw.upgradeCount() >= 1, 20_000L, "mux upgrade"));
            assertEquals("no event notification may be posted without the permission",
                    0, events().size());
        } finally {
            controller.destroy();
        }
        assertFalse(controller.get().isWatching());
    }

    // ------------------------------------------------------------------ helpers

    private ServiceController<GatewayWatchService> create() {
        return Robolectric.buildService(GatewayWatchService.class,
                new Intent(appContext(), GatewayWatchService.class)
                        .setAction(GatewayWatchService.ACTION_START)).create();
    }

    private ServiceController<GatewayWatchService> start() {
        ServiceController<GatewayWatchService> controller = create();
        controller.startCommand(0, 0);
        assertTrue("the service never opened the mux",
                waitFor(() -> gw.upgradeCount() >= 1, 20_000L, "mux upgrade"));
        assertTrue("the service never listed sessions", waitForRequests(gw, 1));
        return controller;
    }

    /** The mux socket the service itself opened (it connects before any Activity does). */
    private StubGateway.FrameSink sink() {
        synchronized (sinks) {
            assertFalse("the service never opened a mux socket", sinks.isEmpty());
            return sinks.get(0);
        }
    }

    /** Pushes {@code frame} until {@code deadlineMs} elapsed, retrying every 100 ms. */
    private boolean pushUntil(StubGateway.FrameSink sink, String frame, long deadlineMs) {
        long deadline = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < deadline) {
            if (!events().isEmpty()) {
                return true;
            }
            if (!push(sink, frame)) {
                return false;
            }
            idle();
            sleep(100);
        }
        return !events().isEmpty();
    }

    private void pushFor(StubGateway.FrameSink sink, String frame, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (!push(sink, frame)) {
                return;
            }
            idle();
            sleep(100);
        }
    }

    private boolean push(StubGateway.FrameSink sink, String frame) {
        try {
            sink.send(frame);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, Object> sessionList() {
        List<Object> items = Json.arr();
        for (String sessionId : running) {
            Map<String, Object> item = Json.obj();
            item.put("sessionId", sessionId);
            item.put("running", Boolean.TRUE);
            item.put("updatedAt", System.currentTimeMillis());
            item.put("cwd", "/work");
            items.add(item);
        }
        Map<String, Object> value = Json.obj();
        value.put("items", items);
        return value;
    }

    private static Map<String, Object> turnEnd(long turn, String reason) {
        Map<String, Object> data = Json.obj();
        data.put("turn", Long.valueOf(turn));
        data.put("reason", reason);
        return data;
    }

    /**
     * The <em>event</em> notifications only. The foreground service's ongoing notification is
     * also in the manager (Robolectric's {@code startForeground} posts it, exactly as the
     * platform does), and it lives on {@link Notify#CHANNEL_SERVICE}, so the channel separates
     * the two.
     */
    private List<Notification> events() {
        NotificationManager nm =
                (NotificationManager) appContext().getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = Shadows.shadowOf(nm);
        List<Notification> out = new ArrayList<Notification>();
        for (Notification n : shadow.getAllNotifications()) {
            if (Notify.CHANNEL_EVENTS.equals(n.getChannelId())) {
                out.add(n);
            }
        }
        return out;
    }
}
