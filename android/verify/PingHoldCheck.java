package com.dsh.remote.verify;

import com.dsh.remote.net.DshRpcClient;
import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.Http;
import com.dsh.remote.net.Json;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PingHoldCheck —— holds a {@code /api/remote.mux} WebSocket open for N seconds and reports how
 * much traffic arrived.
 *
 * <p>Why this exists: dsh sends a WebSocket <b>Ping every 2000 ms</b> and terminates any client
 * that does not answer with a Pong. {@link VerifyMain} finishes a turn in well under 2 s, so it
 * usually never sees a Ping. This checker deliberately idles on the socket for longer than the
 * heartbeat interval, which is the only way to actually exercise the Ping/Pong path against a
 * live server. Run it against the mock gateway to see the Pong count on the server side.
 *
 * <pre>
 * Usage:
 *   java com.dsh.remote.verify.PingHoldCheck --url &lt;baseUrl&gt; --user &lt;u&gt; --pass &lt;p&gt;
 *        [--seconds 8] [--insecure]
 * </pre>
 *
 * Exit codes: 0 = the socket stayed open for the whole hold window, 1 = it did not, 2 = unreachable.
 */
public final class PingHoldCheck {

    public static void main(String[] args) {
        String url = null;
        String user = null;
        String pass = null;
        int seconds = 8;
        boolean insecure = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--url".equals(a) && i + 1 < args.length) {
                url = args[++i];
            } else if ("--user".equals(a) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--pass".equals(a) && i + 1 < args.length) {
                pass = args[++i];
            } else if ("--seconds".equals(a) && i + 1 < args.length) {
                seconds = Integer.parseInt(args[++i]);
            } else if ("--insecure".equals(a)) {
                insecure = true;
            } else if (url == null) {
                url = a;
            } else if (user == null) {
                user = a;
            } else if (pass == null) {
                pass = a;
            }
        }
        if (url == null || user == null || pass == null) {
            System.out.println("Usage: PingHoldCheck --url <baseUrl> --user <u> --pass <p> "
                    + "[--seconds 8] [--insecure]");
            System.exit(1);
        }
        if (insecure) {
            Http.setInsecureTls(true);
        }

        System.out.println("PingHoldCheck: " + url + "  hold=" + seconds + "s");
        Http http = new Http(url);
        http.setConnectTimeoutMs(15000);
        http.setReadTimeoutMs(120000);

        String token;
        try {
            GatewayClient.LoginResult lr = new GatewayClient(url).login(user, pass);
            token = lr.token;
        } catch (IOException e) {
            System.out.println("CANNOT REACH / LOG IN: " + e);
            System.exit(2);
            return;
        }
        http.setBearer(token);
        System.out.println("  logged in, token=" + abbrev(token, 32));

        final DshRpcClient rpc = new DshRpcClient(http);
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger values = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();

        try {
            rpc.openEvents("ev", new DshRpcClient.StreamListener() {
                public void onValue(Object value) {
                    values.incrementAndGet();
                    System.out.println("  [value] " + abbrev(Json.stringify(value), 160));
                    opened.countDown();
                }

                public void onError(Object error) {
                    errors.incrementAndGet();
                    System.out.println("  [error] " + Json.stringify(error));
                    closed.countDown();
                }

                public void onEnd() {
                    System.out.println("  [end]");
                    closed.countDown();
                }
            });
        } catch (IOException e) {
            System.out.println("  opening $events failed: " + e);
            System.exit(1);
            return;
        }

        try {
            opened.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("  mux open; idling " + seconds + "s so the server heartbeat can fire ...");

        boolean stayedOpen = true;
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (closed.await(250, TimeUnit.MILLISECONDS)) {
                    stayedOpen = false;
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean openNow = rpc.mux().isOpen();
        System.out.println("  values=" + values.get() + " errors=" + errors.get()
                + " stillOpen=" + openNow);
        rpc.mux().close();

        if (stayedOpen && openNow) {
            System.out.println("RESULT: socket survived a " + seconds + "s idle hold "
                    + "(server Pings were answered; check the server-side Pong count).");
            System.exit(0);
        }
        System.out.println("RESULT: socket did NOT survive the idle hold.");
        System.exit(1);
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
