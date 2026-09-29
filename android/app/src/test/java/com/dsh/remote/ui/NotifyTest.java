package com.dsh.remote.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowToast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The notification surface (Task B): channels, the two event notifications, the ongoing
 * service notification, foreground-session suppression and the {@code POST_NOTIFICATIONS}
 * path.
 *
 * <p>Every assertion goes through {@link ShadowNotificationManager} — i.e. through the real
 * {@code NotificationManager.notify()} call the app makes, not through a helper of ours.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NotifyTest extends UiTestBase {

    @Before
    public void setUp() {
        Notify.resetForTests();
        TokenCipher.resetForTests();
        prefs().edit().clear().commit();
        // Robolectric denies runtime permissions by default. Granting POST_NOTIFICATIONS here
        // is the "user allowed notifications" state; the denied tests revoke it explicitly.
        Shadows.shadowOf((Application) appContext())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void tearDown() {
        Notify.resetForTests();
        TokenCipher.resetForTests();
    }

    // ------------------------------------------------------------------ channels

    @Test
    public void createsTheEventAndServiceChannelsWithTheExpectedIdsAndImportance() {
        Notify.createChannels(appContext());

        NotificationChannel events = manager().getNotificationChannel(Notify.CHANNEL_EVENTS);
        NotificationChannel service = manager().getNotificationChannel(Notify.CHANNEL_SERVICE);

        assertNotNull("the event channel was never created", events);
        assertNotNull("the service channel was never created", service);
        assertEquals("dsh_remote_events", events.getId());
        assertEquals("event notifications must be visible (DEFAULT)",
                NotificationManager.IMPORTANCE_DEFAULT, events.getImportance());
        assertEquals("dsh_remote_service", service.getId());
        assertEquals("the ongoing service notification must be low importance",
                NotificationManager.IMPORTANCE_LOW, service.getImportance());
        assertNotEquals("the ongoing notification must not share the event channel",
                events.getId(), service.getId());
    }

    // ------------------------------------------------------------------ event notifications

    @Test
    public void aTurnCompletionPostsANotificationWithTheExpectedTitleAndText() {
        Notify.notifyTurnEnd(appContext(), "sess-001", 3, "completed");

        assertEquals(1, posted().size());
        Notification n = posted().get(0);
        assertEquals("Turn finished", title(n));
        assertEquals("Session sess-001 - turn 3 ended (completed)", text(n));
        assertEquals(Notify.CHANNEL_EVENTS, n.getChannelId());
    }

    @Test
    public void anApprovalRequestPostsANotification() {
        Notify.notifyApproval(appContext(), "sess-001", "shell", "wants to delete build/", "ev-1");

        assertEquals(1, posted().size());
        Notification n = posted().get(0);
        assertEquals("Approval needed", title(n));
        assertEquals("Tool shell is waiting for your approval: wants to delete build/", text(n));
        assertEquals(Notify.CHANNEL_EVENTS, n.getChannelId());
    }

    @Test
    public void anApprovalWithoutAToolNameOrReasonStillReadsSensibly() {
        Notify.notifyApproval(appContext(), null, null, null, "ev-2");

        assertEquals(1, posted().size());
        assertEquals("Tool unknown tool is waiting for your approval", text(posted().get(0)));
        Intent tap = Shadows.shadowOf(posted().get(0).contentIntent).getSavedIntent();
        assertEquals("an approval with no known session opens the session list",
                SessionListActivity.class.getName(), tap.getComponent().getClassName());
    }

    @Test
    public void aTurnEndWithoutANumberOrReasonStillReadsSensibly() {
        Notify.notifyTurnEnd(appContext(), "sess-001", -1, null);

        assertEquals("Session sess-001 - turn ? ended (unknown)", text(posted().get(0)));
    }

    @Test
    public void longSessionIdsAreShortenedInTheNotificationText() {
        Notify.notifyTurnEnd(appContext(), "0123456789abcdef", 2, "cancelled");

        assertEquals("Session 01234567\u2026 - turn 2 ended (cancelled)", text(posted().get(0)));
    }

    @Test
    public void twoSessionsProduceTwoSeparateNotifications() {
        Notify.notifyTurnEnd(appContext(), "sess-001", 1, "completed");
        Notify.notifyTurnEnd(appContext(), "sess-002", 1, "completed");

        assertEquals("notifications for different sessions must not overwrite each other",
                2, posted().size());
    }

    // ------------------------------------------------------------------ taps

    @Test
    public void tappingATurnNotificationOpensThatSessionsChat() {
        Notify.notifyTurnEnd(appContext(), "sess-042", 1, "completed");

        Intent tap = Shadows.shadowOf(posted().get(0).contentIntent).getSavedIntent();
        assertEquals(ChatActivity.class.getName(), tap.getComponent().getClassName());
        assertEquals("sess-042", tap.getStringExtra(ChatActivity.EXTRA_SESSION_ID));
    }

    @Test
    public void tappingAnApprovalNotificationOpensThatSessionsChat() {
        Notify.notifyApproval(appContext(), "sess-007", "shell", "why not", "ev-9");

        Intent tap = Shadows.shadowOf(posted().get(0).contentIntent).getSavedIntent();
        assertEquals(ChatActivity.class.getName(), tap.getComponent().getClassName());
        assertEquals("sess-007", tap.getStringExtra(ChatActivity.EXTRA_SESSION_ID));
    }

    // ------------------------------------------------------------------ suppression

    /**
     * The rule: an event for the session that is on screen is not notified. The second half of
     * the test is the control — the same call for a different session <em>does</em> notify, so
     * a broken notification path could not make this test pass by accident.
     */
    @Test
    public void noNotificationIsPostedForTheSessionOnScreen() {
        storeLogin(deadBaseUrl(), "admin", "tok");
        ActivityController<ChatActivity> chat = Robolectric.buildActivity(ChatActivity.class,
                new Intent(appContext(), ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_SESSION_ID, "sess-001"))
                .create().start().resume();
        try {
            // Assert the tracked state directly, so a broken *suppression* rule fails on the
            // notification count below rather than on this precondition.
            assertEquals("the resumed ChatActivity's session was not tracked",
                    "sess-001", Notify.visibleSessionId());
            assertTrue("the app should count as foreground while the chat is resumed",
                    Notify.isAppForeground());

            Notify.notifyTurnEnd(appContext(), "sess-001", 3, "completed");
            Notify.notifyApproval(appContext(), "sess-001", "shell", "blocked", "ev-1");
            assertEquals("the session on screen must not be notified", 0, posted().size());

            Notify.notifyTurnEnd(appContext(), "sess-002", 3, "completed");
            assertEquals("a different session must still be notified", 1, posted().size());
            assertEquals("Session sess-002 - turn 3 ended (completed)", text(posted().get(0)));
        } finally {
            chat.pause().stop().destroy();
        }
        assertFalse("after leaving the chat the session is no longer suppressed",
                Notify.isSessionVisible("sess-001"));
    }

    // ------------------------------------------------------------------ ongoing notification

    @Test
    public void theOngoingNotificationIsPersistentAndOnTheLowImportanceChannel() {
        Notification n = Notify.ongoingNotification(appContext());

        assertEquals(Notify.CHANNEL_SERVICE, n.getChannelId());
        assertTrue("the foreground-service notification must be ongoing",
                (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals("DSH Remote", title(n));
        assertEquals(Notify.SERVICE_TEXT_WATCHING, text(n));
        Intent tap = Shadows.shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("tapping it opens the app", SessionListActivity.class.getName(),
                tap.getComponent().getClassName());
    }

    @Test
    public void theOngoingNotificationIsDistinctFromEventNotifications() {
        manager().notify(Notify.ONGOING_ID, Notify.ongoingNotification(appContext()));
        Notify.notifyTurnEnd(appContext(), "sess-001", 3, "completed");

        assertEquals("the ongoing notification and the event notification must coexist",
                2, posted().size());
        Set<String> channels = new HashSet<String>();
        for (Notification n : posted()) {
            channels.add(n.getChannelId());
        }
        assertTrue(channels.contains(Notify.CHANNEL_EVENTS));
        assertTrue(channels.contains(Notify.CHANNEL_SERVICE));
    }

    // ------------------------------------------------------------------ permission

    /**
     * {@code POST_NOTIFICATIONS} refused: nothing crashes, nothing is posted, and the state is
     * surfaced (Toast + the ongoing notification's own text) instead of being swallowed.
     */
    @Test
    public void postNotificationsDeniedDoesNotCrashAndIsSurfaced() {
        denyNotifications();

        assertFalse("the POST_NOTIFICATIONS runtime permission must be consulted",
                Notify.notificationsEnabled(appContext()));

        Notify.notifyTurnEnd(appContext(), "sess-001", 3, "completed");
        Notify.notifyApproval(appContext(), "sess-001", "shell", "blocked", "ev-1");

        assertEquals("nothing may be posted without the permission", 0, posted().size());
        assertNotNull("the denied state was never surfaced", ShadowToast.getTextOfLatestToast());
        assertTrue("unexpected toast: " + ShadowToast.getTextOfLatestToast(),
                ShadowToast.getTextOfLatestToast().contains("Notifications are off"));
        assertEquals("the ongoing notification must say notifications are off",
                Notify.SERVICE_TEXT_OFF, text(Notify.ongoingNotification(appContext())));
    }

    @Test
    public void theRuntimePermissionIsRequestedFromTheFirstActivityThatResumes() {
        denyNotifications();

        ActivityController<LoginActivity> controller =
                Robolectric.buildActivity(LoginActivity.class).create().start().resume();
        try {
            ShadowActivity.PermissionsRequest request =
                    Shadows.shadowOf(controller.get()).getLastRequestedPermission();
            assertNotNull("POST_NOTIFICATIONS was never requested", request);
            assertEquals(1, request.requestedPermissions.length);
            assertEquals(Manifest.permission.POST_NOTIFICATIONS, request.requestedPermissions[0]);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void anEventIsStillNotifiedWhenNotificationsAreEnabled() {
        // control for the denied test above: same call, permission granted -> it is posted
        assertTrue(Notify.notificationsEnabled(appContext()));
        Notify.notifyTurnEnd(appContext(), "sess-001", 3, "completed");
        assertEquals(1, posted().size());
    }

    // ------------------------------------------------------------------ helpers

    private NotificationManager manager() {
        return (NotificationManager) appContext().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private ShadowNotificationManager shadowManager() {
        return Shadows.shadowOf(manager());
    }

    private List<Notification> posted() {
        return shadowManager().getAllNotifications();
    }

    private static String title(Notification n) {
        return n.extras.getString(Notification.EXTRA_TITLE);
    }

    private static String text(Notification n) {
        return n.extras.getString(Notification.EXTRA_TEXT);
    }

    private void denyNotifications() {
        Shadows.shadowOf((Application) appContext())
                .denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** A base URL nothing listens on, so a ChatActivity started here fails fast and quietly. */
    private static String deadBaseUrl() {
        try {
            java.net.ServerSocket dead = new java.net.ServerSocket(0);
            int port = dead.getLocalPort();
            dead.close();
            return "http://127.0.0.1:" + port;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
