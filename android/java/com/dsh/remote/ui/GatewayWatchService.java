package com.dsh.remote.ui;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.dsh.remote.net.DshRpcClient;
import com.dsh.remote.net.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the gateway mux open while the app is not on screen and turns
 * the two events the user cares about into local notifications.
 *
 * <p>What it watches, on one background thread:
 * <ol>
 *   <li>{@code $events} — the forwarded-event firehose. A {@code waterfall} frame for
 *       {@code approval/request} means the host turn is <b>blocked</b> until it is answered,
 *       so it is notified immediately. (The frame carries an {@code agentId}, not a session
 *       id, so the notification's session hint is the last chat the user opened; see
 *       {@code android/README.md} §8.)</li>
 *   <li>{@code session/follow} for every session {@code session/list} reports as running
 *       ({@code assistantStream:false} — only the durable {@code turn/end} event is wanted,
 *       not the token stream). That is what produces "the turn finished" notifications.</li>
 * </ol>
 *
 * <p>It is a foreground service because Android kills a plain background service within
 * minutes; the ongoing notification on {@link Notify#CHANNEL_SERVICE} is the price of that and
 * is deliberately separate from the event notifications.
 *
 * <p>Nothing here is fatal: every network or Keystore failure is logged, the loop retries on
 * the next tick, and {@link #isWatching()} stays honest about the state for tests.
 */
public final class GatewayWatchService extends Service {

    public static final String ACTION_START = "com.dsh.remote.ui.action.WATCH_START";
    public static final String ACTION_STOP = "com.dsh.remote.ui.action.WATCH_STOP";

    /** How often {@code session/list} is polled to discover (and drop) running sessions. */
    static final long POLL_INTERVAL_MS = 5000L;
    /** Upper bound on simultaneously followed sessions, so a busy host cannot fan out. */
    static final int MAX_FOLLOWED_SESSIONS = 4;
    static final String EVENTS_STREAM = "watch-events";

    private static final String TAG = "DshRemote";

    private ExecutorService executor;
    private final AtomicBoolean loopStarted = new AtomicBoolean(false);
    private final Map<String, String> followed = new LinkedHashMap<String, String>();

    private volatile boolean stopped;
    private volatile boolean eventsOpen;
    private volatile DshRpcClient rpc;
    private volatile String rpcToken = "";
    private volatile String lastError;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.createChannels(this);
        startForeground(Notify.ONGOING_ID, Notify.ongoingNotification(this));
        executor = Executors.newSingleThreadExecutor(App.daemonThreads("watch"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        stopped = false;
        // Re-post every time: the text depends on whether notifications are actually enabled.
        startForeground(Notify.ONGOING_ID, Notify.ongoingNotification(this));
        if (loopStarted.compareAndSet(false, true)) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    watchLoop();
                }
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        loopStarted.set(false);
        DshRpcClient client = rpc;
        if (client != null) {
            try {
                client.mux().close();
            } catch (Throwable t) {
                Log.w(TAG, "cannot close the watch mux: " + t);
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ state (tests)

    /** True between a successful start and stop/destroy. */
    boolean isWatching() {
        return !stopped && loopStarted.get();
    }

    /** The last failure of the watch loop, or null when the last tick was clean. */
    String lastError() {
        return lastError;
    }

    int followedCount() {
        synchronized (followed) {
            return followed.size();
        }
    }

    // ------------------------------------------------------------------ loop

    private void watchLoop() {
        while (!stopped) {
            try {
                if (App.getToken(this).isEmpty()) {
                    // Logged out (or the token was cleared): nothing left to watch.
                    stopSelf();
                    return;
                }
                ensureEvents();
                pollSessions();
                lastError = null;
            } catch (Throwable t) {
                lastError = messageOf(t);
                Log.w(TAG, "watch tick failed: " + lastError);
                eventsOpen = false;
                // Cancel (not just forget) every follow: a half-failed tick may have left the
                // stream registered on the host, and re-opening it would fail forever.
                dropAllFollows();
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
    }

    /** Opens {@code $events} on the mux, reconnecting the socket when it died. */
    private void ensureEvents() throws Exception {
        String token = App.getToken(this);
        if (rpc == null || !token.equals(rpcToken)) {
            DshRpcClient old = rpc;
            if (old != null) {
                try {
                    old.mux().close();
                } catch (Throwable ignore) {
                    // replacing the client anyway
                }
            }
            rpc = App.rpc(this);
            rpcToken = token;
            eventsOpen = false;
        }
        DshRpcClient client = rpc;
        if (eventsOpen && client.mux().isOpen()) {
            return;
        }
        // A dead socket drops its streams, but an error/end frame may have left this one
        // registered; cancel is a no-op when it is not, so it is always safe here.
        client.mux().cancel(EVENTS_STREAM);
        client.openEvents(EVENTS_STREAM, eventsListener);
        eventsOpen = true;
    }

    private void pollSessions() throws Exception {
        List<Object> items = rpc.sessionList();
        List<String> running = new ArrayList<String>();
        for (Object item : items) {
            Map<String, Object> m = App.asMap(item);
            if (m == null || !Json.bool(m, false, "running")) {
                continue;
            }
            String sessionId = Json.str(m, "sessionId");
            if (sessionId == null || sessionId.isEmpty()) {
                continue;
            }
            running.add(sessionId);
            followSession(sessionId);
        }
        List<String> gone = new ArrayList<String>();
        synchronized (followed) {
            for (String sessionId : followed.keySet()) {
                if (!running.contains(sessionId)) {
                    gone.add(sessionId);
                }
            }
        }
        for (String sessionId : gone) {
            unfollow(sessionId);
        }
    }

    private void followSession(String sessionId) throws Exception {
        String streamId = streamIdFor(sessionId);
        synchronized (followed) {
            if (followed.containsKey(sessionId) || followed.size() >= MAX_FOLLOWED_SESSIONS) {
                return;
            }
            followed.put(sessionId, streamId);
        }
        try {
            Map<String, Object> address = Json.obj();
            address.put("kind", "session");
            address.put("sessionId", sessionId);
            Map<String, Object> request = Json.obj();
            request.put("address", address);
            // Only the durable event frames (turn/end) are wanted, not every token.
            request.put("assistantStream", Boolean.FALSE);
            Map<String, Object> args = Json.obj();
            args.put("request", request);
            rpc.mux().open(streamId, "session/follow", args, new SessionListener(sessionId));
        } catch (Exception e) {
            forget(sessionId);
            throw e;
        }
    }

    private void unfollow(String sessionId) {
        String streamId;
        synchronized (followed) {
            streamId = followed.remove(sessionId);
        }
        if (streamId == null || rpc == null) {
            return;
        }
        try {
            rpc.mux().cancel(streamId);
        } catch (Throwable t) {
            Log.w(TAG, "cannot cancel " + streamId + ": " + t);
        }
    }

    private void forget(String sessionId) {
        synchronized (followed) {
            followed.remove(sessionId);
        }
    }

    private void dropAllFollows() {
        List<String> open;
        synchronized (followed) {
            open = new ArrayList<String>(followed.keySet());
        }
        for (String sessionId : open) {
            unfollow(sessionId);
        }
    }

    static String streamIdFor(String sessionId) {
        return "watch-" + sessionId;
    }

    // ------------------------------------------------------------------ listeners

    private final DshRpcClient.StreamListener eventsListener = new DshRpcClient.StreamListener() {
        @Override
        public void onValue(Object value) {
            Map<String, Object> frame = App.asMap(value);
            if (frame == null) {
                return;
            }
            String type = Json.str(frame, "type");
            if ("waterfall".equals(type) && "approval/request".equals(Json.str(frame, "event"))) {
                Map<String, Object> request = App.asMap(frame.get("request"));
                String tool = request == null ? null : Json.str(request, "toolName");
                if (tool == null && request != null) {
                    tool = Json.str(request, "name");
                }
                String reason = request == null ? null : Json.str(request, "reason");
                Notify.notifyApproval(GatewayWatchService.this, sessionHint(), tool, reason,
                        Json.str(frame, "eventId"));
            }
            // ready/emit/cancel and unknown shapes need no notification.
        }

        @Override
        public void onError(Object error) {
            eventsOpen = false;
        }

        @Override
        public void onEnd() {
            eventsOpen = false;
        }
    };

    /**
     * The session an approval most likely belongs to: the last chat the user opened, else the
     * only followed session, else unknown (the tap then opens the session list).
     */
    private String sessionHint() {
        String last = Notify.lastSessionId();
        if (last != null && !last.isEmpty()) {
            return last;
        }
        synchronized (followed) {
            if (followed.size() == 1) {
                return followed.keySet().iterator().next();
            }
        }
        return null;
    }

    private final class SessionListener implements DshRpcClient.StreamListener {

        private final String sessionId;

        SessionListener(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onValue(Object value) {
            Map<String, Object> frame = App.asMap(value);
            if (frame == null || !"event".equals(Json.str(frame, "type"))) {
                return;
            }
            Map<String, Object> event = App.asMap(frame.get("event"));
            if (event == null || !"turn/end".equals(Json.str(event, "type"))) {
                return;
            }
            Map<String, Object> data = App.asMap(event.get("data"));
            long turn = data == null ? -1L : Json.num(data, -1L, "turn");
            String reason = data == null ? null : Json.str(data, "reason");
            Notify.notifyTurnEnd(GatewayWatchService.this, sessionId, turn, reason);
        }

        @Override
        public void onError(Object error) {
            forget(sessionId);
        }

        @Override
        public void onEnd() {
            forget(sessionId);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return "unknown failure";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
