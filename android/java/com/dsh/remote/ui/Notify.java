package com.dsh.remote.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Local notifications for the two events worth waking the user for: an agent <b>turn
 * completing</b> and an <b>approval request</b> (the agent is blocked until it is answered).
 *
 * <p>They are posted by {@link GatewayWatchService}, which holds the gateway mux open in the
 * background. There is no FCM in this project: see {@code android/README.md} §8 for exactly
 * what that does and does not cover.
 *
 * <h3>Channels</h3>
 * Two channels, deliberately distinct: {@value #CHANNEL_EVENTS} (IMPORTANCE_DEFAULT — the
 * event notifications the user asked for) and {@value #CHANNEL_SERVICE} (IMPORTANCE_LOW — the
 * mandatory ongoing notification of the foreground service, which must not buzz).
 *
 * <h3>Suppression</h3>
 * An event belonging to the session the user is currently looking at is never notified: the
 * resumed {@link ChatActivity} (its {@code EXTRA_SESSION_ID}) is tracked through
 * {@link Application.ActivityLifecycleCallbacks}. While the user is in that chat, the chat
 * itself shows the approval dialog, so a notification on top of it would be noise.
 *
 * <h3>Permission</h3>
 * On API 33+ {@code POST_NOTIFICATIONS} is a runtime permission. It is requested from the
 * first Activity that resumes; if it is refused, nothing crashes and nothing is silently
 * dropped — the ongoing notification says so and a Toast is raised once.
 */
public final class Notify {

    public static final String CHANNEL_EVENTS = "dsh_remote_events";
    public static final String CHANNEL_SERVICE = "dsh_remote_service";
    public static final int ONGOING_ID = 1;

    static final String TITLE_TURN_END = "Turn finished";
    static final String TITLE_APPROVAL = "Approval needed";
    static final String SERVICE_TITLE = "DSH Remote";
    static final String SERVICE_TEXT_WATCHING = "Watching for approvals and turn completions";
    static final String SERVICE_TEXT_OFF =
            "Notifications are off - grant the notification permission";
    static final String TOAST_OFF = "Notifications are off - approval alerts will not appear";

    private static final String TAG = "DshRemote";
    private static final int PERMISSION_REQUEST_CODE = 4711;
    private static final int EVENT_ID_BASE = 1000;

    private Notify() {
    }

    // ------------------------------------------------------------------ state

    private static volatile boolean attached;
    private static volatile int startedActivities;
    private static volatile String visibleSessionId;
    private static volatile String lastChatSessionId;
    private static volatile boolean permissionRequested;
    private static volatile boolean offSurfaced;
    private static volatile Icon cachedIcon;

    // ------------------------------------------------------------------ channels

    /** Creates both channels. Idempotent; safe to call from any component. */
    public static void createChannels(Context ctx) {
        NotificationManager nm = manager(ctx);
        if (nm == null) {
            return;
        }
        try {
            NotificationChannel events = new NotificationChannel(
                    CHANNEL_EVENTS, "Agent events", NotificationManager.IMPORTANCE_DEFAULT);
            events.setDescription("Turn completions and approval requests from dsh sessions");
            NotificationChannel service = new NotificationChannel(
                    CHANNEL_SERVICE, "Background watching", NotificationManager.IMPORTANCE_LOW);
            service.setDescription(
                    "Keeps the gateway connection open so agent events can be delivered");
            service.setShowBadge(false);
            nm.createNotificationChannel(events);
            nm.createNotificationChannel(service);
        } catch (Throwable t) {
            Log.w(TAG, "cannot create notification channels: " + t);
        }
    }

    // ------------------------------------------------------------------ events

    /** A turn finished in {@code sessionId}. Not posted when that session is on screen. */
    static void notifyTurnEnd(Context ctx, String sessionId, long turn, String reason) {
        String text = turnEndText(sessionId, turn, reason);
        postEvent(ctx, "turn:" + sessionId, TITLE_TURN_END, text, sessionId);
    }

    /** The host is blocked on an approval. {@code sessionHint} may be null/unknown. */
    static void notifyApproval(Context ctx, String sessionHint, String toolName, String reason,
                               String eventId) {
        String text = approvalText(toolName, reason);
        postEvent(ctx, "approval:" + (eventId == null ? text : eventId), TITLE_APPROVAL, text,
                sessionHint);
    }

    private static void postEvent(Context ctx, String key, String title, String text,
                                  String sessionId) {
        if (isSessionVisible(sessionId)) {
            // The user is looking at this very session: its own screen already shows it.
            return;
        }
        if (!notificationsEnabled(ctx)) {
            surfaceOff(ctx, "an event was dropped because notifications are disabled");
            return;
        }
        createChannels(ctx);
        int id = idFor(key);
        post(ctx, id, eventNotification(ctx, title, text, sessionId, id));
    }

    /** The event notification itself (visible for testing without going through the store). */
    static Notification eventNotification(Context ctx, String title, String text,
                                          String sessionId, int requestCode) {
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_EVENTS)
                .setSmallIcon(appIcon())
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis());
        PendingIntent tap = tapFor(ctx, sessionId, requestCode);
        if (tap != null) {
            b.setContentIntent(tap);
        }
        return b.build();
    }

    /** The persistent, low-importance notification the foreground service must show. */
    public static Notification ongoingNotification(Context ctx) {
        createChannels(ctx);
        String text = notificationsEnabled(ctx) ? SERVICE_TEXT_WATCHING : SERVICE_TEXT_OFF;
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_SERVICE)
                .setSmallIcon(appIcon())
                .setContentTitle(SERVICE_TITLE)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true);
        PendingIntent tap = tapFor(ctx, null, 0);
        if (tap != null) {
            b.setContentIntent(tap);
        }
        return b.build();
    }

    /** Tapping an event notification opens that session's chat (or the session list). */
    private static PendingIntent tapFor(Context ctx, String sessionId, int requestCode) {
        try {
            Intent intent = sessionId == null || sessionId.isEmpty()
                    ? new Intent(ctx, SessionListActivity.class)
                    : new Intent(ctx, ChatActivity.class)
                            .putExtra(ChatActivity.EXTRA_SESSION_ID, sessionId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return PendingIntent.getActivity(ctx, requestCode, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Throwable t) {
            Log.w(TAG, "cannot build the notification tap intent: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ text

    static String turnEndText(String sessionId, long turn, String reason) {
        return "Session " + shortId(sessionId)
                + " - turn " + (turn < 0 ? "?" : String.valueOf(turn))
                + " ended (" + (isBlank(reason) ? "unknown" : reason) + ")";
    }

    static String approvalText(String toolName, String reason) {
        String tool = isBlank(toolName) ? "unknown tool" : toolName;
        return "Tool " + tool + " is waiting for your approval"
                + (isBlank(reason) ? "" : ": " + reason);
    }

    private static String shortId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return "?";
        }
        if (sessionId.length() > 12) {
            return sessionId.substring(0, 8) + "\u2026";
        }
        return sessionId;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int idFor(String key) {
        return EVENT_ID_BASE + (Math.abs(key.hashCode()) % 100000);
    }

    // ------------------------------------------------------------------ permission

    /**
     * True when a notification posted now would actually be shown. On API 33+ that needs the
     * runtime permission <em>and</em> the user's channel/app-level switch; the explicit
     * permission check matters because {@code areNotificationsEnabled()} alone reports true on
     * some builds even when {@code POST_NOTIFICATIONS} was refused.
     */
    static boolean notificationsEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            NotificationManager nm = manager(ctx);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            Log.w(TAG, "cannot read the notification state: " + t);
            return false;
        }
    }

    /**
     * Asks for {@code POST_NOTIFICATIONS} once per process; if that is not possible (or was
     * already refused), says so out loud instead of quietly doing nothing.
     */
    private static void askOrSurface(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33 && !permissionRequested
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissionRequested = true;
            try {
                activity.requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
                return;
            } catch (Throwable t) {
                surfaceOff(activity, "the permission request failed: " + t);
                return;
            }
        }
        surfaceOff(activity, "the notification permission is not granted");
    }

    /** Surfaces "notifications are off" exactly once per process (Toast + logcat). */
    private static void surfaceOff(Context ctx, String why) {
        if (offSurfaced) {
            return;
        }
        offSurfaced = true;
        Log.w(TAG, "notifications are OFF (" + why + "); approval alerts will not be shown");
        toastOnMain(ctx, TOAST_OFF);
    }

    /**
     * Toasts are raised from two very different threads: an Activity's {@code onResume} (the
     * main thread) and the watcher's mux reader thread. Marshal to the main looper rather than
     * relying on {@code Toast} tolerating a thread without one.
     */
    private static void toastOnMain(final Context ctx, final String message) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                App.toast(ctx, message);
                return;
            }
            final Context app = ctx.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        App.toast(app, message);
                    } catch (Throwable t) {
                        Log.w(TAG, "cannot show the notifications-off toast: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "cannot raise the notifications-off toast: " + t);
        }
    }

    // ------------------------------------------------------------------ service control

    /** Starts the watching foreground service when a session is stored. Idempotent. */
    static void ensureWatching(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            if (App.getToken(ctx).isEmpty()) {
                return;
            }
            createChannels(ctx);
            Intent intent = new Intent(ctx, GatewayWatchService.class)
                    .setAction(GatewayWatchService.ACTION_START);
            ctx.startForegroundService(intent);
        } catch (Throwable t) {
            // Background-start restrictions, a missing service, a locked-down ROM: never fatal.
            Log.w(TAG, "cannot start the watch service: " + t);
        }
    }

    /** Stops it (logout). */
    static void stopWatching(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, GatewayWatchService.class));
        } catch (Throwable t) {
            Log.w(TAG, "cannot stop the watch service: " + t);
        }
    }

    // ------------------------------------------------------------------ foreground tracking

    /**
     * Registers the activity lifecycle tracker once per process. Called from {@link App}'s
     * store accessors, so it is in place before any Activity can resume.
     */
    static void attach(Context ctx) {
        if (attached || ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (!(app instanceof Application)) {
            return;
        }
        attached = true;
        try {
            ((Application) app).registerActivityLifecycleCallbacks(new Tracker());
        } catch (Throwable t) {
            attached = false;
            Log.w(TAG, "cannot register activity lifecycle callbacks: " + t);
        }
    }

    private static final class Tracker implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
            startedActivities++;
        }

        @Override
        public void onActivityResumed(Activity activity) {
            String sessionId = sessionIdOf(activity);
            visibleSessionId = sessionId;
            if (sessionId != null) {
                lastChatSessionId = sessionId;
            }
            if (!notificationsEnabled(activity)) {
                askOrSurface(activity);
            } else {
                ensureWatching(activity);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            String sessionId = sessionIdOf(activity);
            if (sessionId != null && sessionId.equals(visibleSessionId)) {
                visibleSessionId = null;
            }
        }

        @Override
        public void onActivityStopped(Activity activity) {
            if (startedActivities > 0) {
                startedActivities--;
            }
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            String sessionId = sessionIdOf(activity);
            if (sessionId != null && sessionId.equals(visibleSessionId)) {
                visibleSessionId = null;
            }
        }
    }

    /** The session a resumed {@link ChatActivity} is showing, else null. */
    private static String sessionIdOf(Activity activity) {
        if (!(activity instanceof ChatActivity)) {
            return null;
        }
        try {
            String sessionId = activity.getIntent().getStringExtra(ChatActivity.EXTRA_SESSION_ID);
            return sessionId == null || sessionId.isEmpty() ? null : sessionId;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True while {@code sessionId} is the session on screen (so it must not be notified). */
    static boolean isSessionVisible(String sessionId) {
        String visible = visibleSessionId;
        return sessionId != null && !sessionId.isEmpty()
                && sessionId.equals(visible) && startedActivities > 0;
    }

    static boolean isAppForeground() {
        return startedActivities > 0;
    }

    /** The session of the most recent {@link ChatActivity}; survives backgrounding. */
    static String lastSessionId() {
        return lastChatSessionId;
    }

    static String visibleSessionId() {
        return visibleSessionId;
    }

    // ------------------------------------------------------------------ plumbing

    private static void post(Context ctx, int id, Notification notification) {
        NotificationManager nm = manager(ctx);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(id, notification);
        } catch (Throwable t) {
            Log.w(TAG, "cannot post the notification: " + t);
        }
    }

    private static NotificationManager manager(Context ctx) {
        if (ctx == null) {
            return null;
        }
        try {
            return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A monochrome status-bar icon drawn in code. The hand-rolled pipeline generates no
     * {@code R} class, so a framework drawable id would be resolved against this package on
     * the device and fail to load; a bitmap always resolves.
     */
    private static Icon appIcon() {
        Icon icon = cachedIcon;
        if (icon != null) {
            return icon;
        }
        int size = 24;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size * 0.50f, size * 0.40f, size * 0.32f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.14f);
        canvas.drawLine(size * 0.30f, size * 0.70f, size * 0.30f, size * 0.94f, paint);
        icon = Icon.createWithBitmap(bitmap);
        cachedIcon = icon;
        return icon;
    }

    /** Test-only: forget process-wide state (Robolectric reuses the sandbox classloader). */
    static void resetForTests() {
        attached = false;
        startedActivities = 0;
        visibleSessionId = null;
        lastChatSessionId = null;
        permissionRequested = false;
        offSurfaced = false;
        cachedIcon = null;
    }
}
