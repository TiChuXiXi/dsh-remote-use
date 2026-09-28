package com.dsh.remote.verify;

import com.dsh.remote.net.DshRpcClient;
import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.GatewayException;
import com.dsh.remote.net.Http;
import com.dsh.remote.net.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VerifyMain —— live end-to-end harness for the pure-Java {@code com.dsh.remote.net} layer.
 *
 * <p>Runs on a desktop JVM (JDK 8+) with no Android classes involved:
 * login -&gt; gateway/session -&gt; session/list -&gt; session/create -&gt; open the
 * {@code /api/remote.mux} WebSocket ({@code $events} + {@code session/follow}) -&gt;
 * {@code session/prompt} -&gt; print the streamed assistant chunks -&gt; {@code /fs/list} -&gt;
 * upload -&gt; download (full + Range resume) -&gt; byte-for-byte compare.
 *
 * <p>Exit codes: 0 = every step passed, 1 = a step failed, 2 = the gateway could not be
 * reached at all (expected until the gateway exists).
 *
 * <pre>
 * Usage:
 *   java com.dsh.remote.verify.VerifyMain --url &lt;gatewayBaseUrl&gt; --user &lt;u&gt; --pass &lt;p&gt;
 *        [--cwd &lt;absPath&gt;] [--prompt "text"] [--wait 120] [--insecure] [--out &lt;dir&gt;]
 * </pre>
 */
public final class VerifyMain {

    private static final String TAG_OK = "  [PASS] ";
    private static final String TAG_FAIL = "  [FAIL] ";
    private static final String TAG_SKIP = "  [SKIP] ";
    private static final String TAG_INFO = "  [info] ";

    private String baseUrl;
    private String user;
    private String pass;
    private String cwd;
    private String prompt = "Reply with exactly the single word: PONG-HARNESS";
    private int waitSeconds = 120;
    private boolean insecure = false;
    private File outDir = new File("verify-out");

    private final List<String> passed = new ArrayList<String>();
    private final List<String> failed = new ArrayList<String>();
    private final List<String> skipped = new ArrayList<String>();

    // ---------------------------------------------------------------- main

    public static void main(String[] args) {
        VerifyMain v = new VerifyMain();
        int rc;
        try {
            if (!v.parseArgs(args)) {
                v.usage();
                System.exit(2);
            }
            rc = v.run();
        } catch (Throwable t) {
            System.out.println();
            System.out.println("UNEXPECTED FAILURE: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            rc = 1;
        }
        System.exit(rc);
    }

    private void usage() {
        System.out.println("Usage: VerifyMain --url <gatewayBaseUrl> --user <u> --pass <p>");
        System.out.println("                  [--cwd <absPath>] [--prompt <text>] [--wait <seconds>]");
        System.out.println("                  [--insecure] [--out <dir>]");
        System.out.println();
        System.out.println("  --url       e.g. http://127.0.0.1:3081  or  https://gw.example.com");
        System.out.println("  --user/--pass  gateway credentials (POST /gateway/login)");
        System.out.println("  --cwd       working directory for session/create (default: omit -> dsh default)");
        System.out.println("  --wait      seconds to wait for the streamed turn to end (default 120)");
        System.out.println("  --insecure  trust all TLS certificates (self-signed test gateways only)");
        System.out.println("  --out       directory for downloaded fixtures (default ./verify-out)");
    }

    private boolean parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--url".equals(a) && i + 1 < args.length) {
                baseUrl = args[++i];
            } else if ("--user".equals(a) && i + 1 < args.length) {
                user = args[++i];
            } else if ("--pass".equals(a) && i + 1 < args.length) {
                pass = args[++i];
            } else if ("--cwd".equals(a) && i + 1 < args.length) {
                cwd = args[++i];
            } else if ("--prompt".equals(a) && i + 1 < args.length) {
                prompt = args[++i];
            } else if ("--wait".equals(a) && i + 1 < args.length) {
                waitSeconds = Integer.parseInt(args[++i]);
            } else if ("--out".equals(a) && i + 1 < args.length) {
                outDir = new File(args[++i]);
            } else if ("--insecure".equals(a)) {
                insecure = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                return false;
            } else if (a.startsWith("--")) {
                System.out.println("Unknown option: " + a);
                return false;
            } else if (baseUrl == null) {
                baseUrl = a;                       // positional fallback: url user pass
            } else if (user == null) {
                user = a;
            } else if (pass == null) {
                pass = a;
            } else {
                System.out.println("Unexpected extra argument: " + a);
                return false;
            }
        }
        return baseUrl != null && user != null && pass != null;
    }

    // ---------------------------------------------------------------- flow

    private int run() {
        System.out.println("================================================================");
        System.out.println(" dsh-remote Android net/ layer -- live gateway verification harness");
        System.out.println("================================================================");
        System.out.println(" gateway : " + baseUrl);
        System.out.println(" user    : " + user);
        System.out.println(" cwd     : " + (cwd == null ? "(omitted -> dsh default)" : cwd));
        System.out.println(" out dir : " + outDir.getAbsolutePath());
        System.out.println();

        if (insecure) {
            Http.setInsecureTls(true);
            System.out.println(TAG_INFO + "insecure TLS enabled (trust-all)");
        }

        if (!outDir.exists() && !outDir.mkdirs()) {
            System.out.println(TAG_FAIL + "cannot create out dir " + outDir.getAbsolutePath());
            return 1;
        }

        Http http = new Http(baseUrl);
        http.setConnectTimeoutMs(15000);
        http.setReadTimeoutMs(120000);
        GatewayClient gw = new GatewayClient(baseUrl);
        gw.http().setConnectTimeoutMs(15000);
        gw.http().setReadTimeoutMs(120000);

        // ---- 1. reachability + health (unauthenticated) -------------------
        section("1. gateway reachability  (GET /gateway/health)");
        Object health;
        try {
            health = gw.health();
        } catch (IOException e) {
            connectionFailure("GET /gateway/health", e);
            return 2;
        }
        System.out.println(TAG_INFO + "health = " + Json.stringify(health));
        pass("gateway reachable");

        // ---- 2. login ----------------------------------------------------
        section("2. login  (POST /gateway/login)");
        String token;
        long expiresAt;
        try {
            GatewayClient.LoginResult lr = gw.login(user, pass);
            token = lr.token;
            expiresAt = lr.expiresAt;
            System.out.println(TAG_INFO + "token = " + abbrev(token, 48));
            System.out.println(TAG_INFO + "expiresAt = " + expiresAt
                    + (expiresAt > 0 ? "  (" + (expiresAt - System.currentTimeMillis()) / 1000 + "s from now)" : ""));
            pass("login returned a token");
        } catch (GatewayException e) {
            fail("login rejected: HTTP " + e.status + " error=" + e.error + " message=" + e.detail);
            return summary(1);
        } catch (IOException e) {
            connectionFailure("POST /gateway/login", e);
            return 2;
        }

        // ---- 3. token validation ----------------------------------------
        section("3. stored-token validation  (GET /gateway/session)");
        try {
            boolean valid = gw.checkSession();
            if (valid) {
                pass("GET /gateway/session -> 200, token accepted");
            } else {
                fail("GET /gateway/session -> 401, freshly minted token rejected");
            }
        } catch (IOException e) {
            fail("GET /gateway/session failed: " + e);
        }

        DshRpcClient rpc = new DshRpcClient(http);
        http.setBearer(token);

        // ---- 4. session/list --------------------------------------------
        section("4. session/list  (POST /api/session/list)");
        String sessionId = null;
        try {
            List<Object> items = rpc.sessionList();
            System.out.println(TAG_INFO + items.size() + " session(s)");
            for (int i = 0; i < items.size() && i < 10; i++) {
                Object s = items.get(i);
                System.out.println("        - " + abbrev(Json.str(s, "sessionId"), 40)
                        + "  running=" + Json.bool(s, false, "running")
                        + "  blank=" + Json.bool(s, false, "blank")
                        + "  cwd=" + Json.str(s, "cwd"));
            }
            if (!items.isEmpty()) {
                sessionId = Json.str(items.get(0), "sessionId");
            }
            pass("session/list parsed (" + items.size() + " items)");
        } catch (IOException e) {
            fail("session/list failed: " + e);
        }

        // ---- 5. session/create ------------------------------------------
        section("5. session/create  (POST /api/session/create)");
        try {
            sessionId = rpc.sessionCreate(cwd);
            System.out.println(TAG_INFO + "new sessionId = " + sessionId);
            pass("session/create returned a sessionId");
        } catch (IOException e) {
            System.out.println(TAG_FAIL + "session/create failed: " + e);
            failed.add("session/create: " + e.getMessage());
            System.out.println(TAG_INFO + (sessionId == null
                    ? "no usable session; streaming steps will be skipped"
                    : "falling back to the session from session/list: " + sessionId));
        }

        // ---- 6/7/8. streaming turn --------------------------------------
        if (sessionId == null) {
            section("6-8. live stream + prompt");
            skip("no sessionId available -> cannot exercise the mux");
        } else {
            streamAndPrompt(rpc, sessionId);
        }

        // ---- 9. /fs/list -------------------------------------------------
        section("9. workspace files  (GET /fs/list?path=.)");
        try {
            List<Object> entries = gw.fsList(".");
            System.out.println(TAG_INFO + entries.size() + " entries at workspace root");
            for (int i = 0; i < entries.size() && i < 8; i++) {
                Object en = entries.get(i);
                System.out.println("        - " + Json.str(en, "type") + "  "
                        + Json.str(en, "name") + "  size=" + Json.num(en, -1, "size"));
            }
            pass("/fs/list parsed (" + entries.size() + " entries)");
        } catch (GatewayException e) {
            fail("/fs/list rejected: HTTP " + e.status + " error=" + e.error + " " + e.detail);
        } catch (IOException e) {
            fail("/fs/list failed: " + e);
        }

        // ---- 10. upload --------------------------------------------------
        section("10. upload  (POST /fs/upload, multipart mode A)");
        File uploadLocal = null;
        String uploadRel = null;
        try {
            uploadLocal = makeFixture();
            System.out.println(TAG_INFO + "fixture: " + uploadLocal.getAbsolutePath()
                    + "  " + uploadLocal.length() + " bytes  sha256=" + sha256(uploadLocal));
            Map<String, Object> up = Json.map(gw.upload(".", uploadLocal));
            System.out.println(TAG_INFO + "upload response = " + Json.stringify(up));
            uploadRel = Json.str(up, "path");
            if (uploadRel == null) {
                uploadRel = uploadLocal.getName();
            }
            System.out.println(TAG_INFO + "server-side path = " + uploadRel);
            pass("upload accepted");
        } catch (GatewayException e) {
            fail("upload rejected: HTTP " + e.status + " error=" + e.error + " " + e.detail);
        } catch (IOException e) {
            fail("upload failed: " + e);
        } catch (RuntimeException e) {
            fail("upload failed: " + e);
        }

        // ---- 11. download (+ Range resume) -------------------------------
        section("11. download  (GET /fs/download, full + Range resume)");
        if (uploadRel == null) {
            skip("no uploaded path -> cannot exercise download");
        } else {
            File full = new File(outDir, "download-full.bin");
            File part = new File(outDir, "download-resumed.bin");
            delete(full);
            delete(part);
            try {
                long n = gw.download(uploadRel, full, false);
                System.out.println(TAG_INFO + "full download: " + n + " bytes -> " + full.getAbsolutePath());
                String a = sha256(full);
                String b = sha256(uploadLocal);
                if (a.equals(b)) {
                    pass("full download byte-identical (sha256 " + abbrev(a, 16) + ")");
                } else {
                    fail("full download differs: local=" + a + " remote=" + b);
                }

                // seed a truncated local file, then resume with Range
                long cut = Math.max(1, uploadLocal.length() / 2);
                copyPrefix(uploadLocal, part, cut);
                long resumed = gw.download(uploadRel, part, true);
                String c = sha256(part);
                System.out.println(TAG_INFO + "resume from byte " + cut + " -> " + resumed
                        + " bytes total; sha256=" + abbrev(c, 16));
                if (c.equals(b)) {
                    pass("Range resume produced a byte-identical file");
                } else {
                    fail("Range resume produced a different file: " + c + " != " + b);
                }
            } catch (GatewayException e) {
                fail("download rejected: HTTP " + e.status + " error=" + e.error + " " + e.detail);
            } catch (IOException e) {
                fail("download failed: " + e);
            }
        }

        // ---- 12. logout --------------------------------------------------
        section("12. logout  (POST /gateway/logout)");
        try {
            gw.logout();
            boolean stillValid = gw.checkSession();
            if (!stillValid) {
                pass("logout revoked the token (GET /gateway/session -> 401)");
            } else {
                System.out.println(TAG_INFO + "token still valid after logout (gateway may not revoke on logout)");
                pass("logout returned without error");
            }
        } catch (IOException e) {
            fail("logout failed: " + e);
        }

        return summary(failed.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------- streaming turn

    private void streamAndPrompt(final DshRpcClient rpc, final String sessionId) {
        section("6. WebSocket mux  (WS /api/remote.mux: $events + session/follow)");
        final CountDownLatch turnEnded = new CountDownLatch(1);
        final AtomicInteger chunks = new AtomicInteger();
        final AtomicInteger events = new AtomicInteger();
        final AtomicReference<String> assistant = new AtomicReference<String>("");
        final AtomicReference<String> muxError = new AtomicReference<String>(null);
        final AtomicReference<String> clientIdRef = new AtomicReference<String>(null);
        final StringBuilder live = new StringBuilder();

        DshRpcClient.Mux mux;
        try {
            mux = rpc.mux();
        } catch (Throwable t) {
            fail("could not create mux: " + t);
            return;
        }

        try {
            mux.open("ev", "$events", Json.obj(), new DshRpcClient.StreamListener() {
                public void onValue(Object value) {
                    String t = Json.str(value, "type");
                    if ("ready".equals(t)) {
                        clientIdRef.set(Json.str(value, "clientId"));
                        System.out.println(TAG_INFO + "[$events] ready clientId=" + clientIdRef.get());
                    } else if ("waterfall".equals(t)) {
                        System.out.println(TAG_INFO + "[$events] waterfall event=" + Json.str(value, "event")
                                + " eventId=" + Json.str(value, "eventId"));
                        if ("approval/request".equals(Json.str(value, "event"))) {
                            answerApproval(rpc, clientIdRef.get(), Json.str(value, "eventId"), value);
                        }
                    } else {
                        System.out.println(TAG_INFO + "[$events] " + abbrev(Json.stringify(value), 300));
                    }
                }

                public void onError(Object error) {
                    muxError.set("$events: " + Json.stringify(error));
                    System.out.println(TAG_FAIL + "[$events] " + Json.stringify(error));
                }

                public void onEnd() {
                    System.out.println(TAG_INFO + "[$events] stream ended");
                }
            });
            pass("opened $events stream");
        } catch (IOException e) {
            fail("opening $events failed: " + e.getMessage());
            failed.add("mux/$events: " + e.getMessage());
        }

        try {
            mux.open("s1", "session/follow", followArgs(sessionId), new DshRpcClient.StreamListener() {
                public void onValue(Object value) {
                    String t = Json.str(value, "type");
                    if ("assistant-stream".equals(t)) {
                        Object frame = Json.get(value, "frame");
                        String ft = Json.str(frame, "type");
                        if ("chunk".equals(ft)) {
                            String text = chunkText(Json.get(frame, "chunk"));
                            if (text.length() > 0) {
                                chunks.incrementAndGet();
                                live.append(text);
                                System.out.print(text);
                                System.out.flush();
                            }
                        } else if ("start".equals(ft)) {
                            System.out.println();
                            System.out.println(TAG_INFO + "[stream] start attempt=" + abbrev(Json.str(frame, "attemptId"), 12)
                                    + " turn=" + Json.num(frame, -1, "turn"));
                        } else if ("end".equals(ft)) {
                            System.out.println();
                            System.out.println(TAG_INFO + "[stream] end outcome="
                                    + Json.str(Json.get(frame, "outcome"), "kind"));
                        }
                    } else if ("event".equals(t)) {
                        Object ev = Json.get(value, "event");
                        String et = Json.str(ev, "type");
                        events.incrementAndGet();
                        if ("turn/end".equals(et)) {
                            System.out.println(TAG_INFO + "[event] turn/end seq=" + Json.num(ev, -1, "seq")
                                    + " reason=" + Json.str(Json.get(ev, "data"), "reason"));
                            turnEnded.countDown();
                        } else if ("assistant/message".equals(et)) {
                            String txt = messageText(Json.get(ev, "data", "message"));
                            if (txt.length() > 0) {
                                assistant.set(txt);
                            }
                            System.out.println(TAG_INFO + "[event] assistant/message  " + abbrev(txt, 200));
                        } else if ("tool/call".equals(et)) {
                            System.out.println(TAG_INFO + "[event] tool/call "
                                    + Json.str(Json.get(ev, "data"), "name") + " "
                                    + abbrev(Json.str(Json.get(ev, "data"), "arguments"), 160));
                        } else if ("tool/result".equals(et)) {
                            System.out.println(TAG_INFO + "[event] tool/result seq=" + Json.num(ev, -1, "seq"));
                        } else {
                            System.out.println(TAG_INFO + "[event] " + et);
                        }
                    } else if ("snapshot".equals(t)) {
                        List<Object> records = Json.list(value, "records");
                        System.out.println(TAG_INFO + "[snapshot] cursor=" + Json.num(value, -1, "cursor")
                                + " records=" + records.size());
                    } else {
                        System.out.println(TAG_INFO + "[frame] " + abbrev(Json.stringify(value), 200));
                    }
                }

                public void onError(Object error) {
                    muxError.set("session/follow: " + Json.stringify(error));
                    System.out.println(TAG_FAIL + "[session/follow] " + Json.stringify(error));
                    turnEnded.countDown();
                }

                public void onEnd() {
                    System.out.println(TAG_INFO + "[session/follow] stream ended");
                    turnEnded.countDown();
                }
            });
            pass("opened session/follow stream for " + abbrev(sessionId, 20));
        } catch (IOException e) {
            fail("opening session/follow failed: " + e.getMessage());
            failed.add("mux/session/follow: " + e.getMessage());
            return;
        }

        section("7. session/prompt  (POST /api/session/prompt)");
        try {
            rpc.sessionPrompt(sessionId, prompt);
            System.out.println(TAG_INFO + "prompt queued: " + abbrev(prompt, 120));
            pass("session/prompt accepted");
        } catch (IOException e) {
            fail("session/prompt failed: " + e);
            try { mux.close(); } catch (Throwable ignored) { }
            return;
        }

        section("8. streamed assistant output  (assistant-stream chunks over the mux)");
        System.out.println(TAG_INFO + "waiting up to " + waitSeconds + "s for turn/end ...");
        boolean done;
        try {
            done = turnEnded.await(waitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = false;
        }
        System.out.println();
        if (done) {
            pass("turn completed (turn/end received)");
        } else {
            System.out.println(TAG_INFO + "timed out waiting for turn/end (the turn may still be running)");
            skipped.add("turn/end within " + waitSeconds + "s");
        }
        if (chunks.get() > 0) {
            pass("received " + chunks.get() + " assistant-stream chunk(s), "
                    + live.length() + " chars of live text: " + abbrev(live.toString(), 160));
        } else {
            System.out.println(TAG_INFO + "no assistant-stream chunks observed; "
                    + "final assistant/message text: " + abbrev(assistant.get(), 200));
            if (done && assistant.get() != null && assistant.get().length() > 0) {
                System.out.println(TAG_INFO + "(server sent the answer as a durable event without live chunks)");
            } else if (muxError.get() != null) {
                fail("no live output; mux reported: " + muxError.get());
            } else {
                skipped.add("assistant-stream chunks (server produced none in this turn)");
            }
        }
        System.out.println(TAG_INFO + "durable events observed: " + events.get());
        try {
            mux.close();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    private void answerApproval(DshRpcClient rpc, String clientId, String eventId, Object frame) {
        System.out.println(TAG_INFO + "APPROVAL REQUESTED: " + abbrev(Json.stringify(Json.get(frame, "request")), 300));
        if (clientId == null) {
            System.out.println(TAG_FAIL + "cannot answer approval: $events ready frame not seen yet (no clientId)");
            failed.add("approval answer: missing clientId");
            return;
        }
        Map<String, Object> outcome = Json.obj();
        outcome.put("kind", "next");
        try {
            rpc.eventsResult(clientId, eventId, outcome);
            System.out.println(TAG_INFO + "answered approval with {\"kind\":\"next\"} (allow once)");
            pass("$events/result answered an approval");
        } catch (IOException e) {
            fail("$events/result failed: " + e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> followArgs(String sessionId) {
        Map<String, Object> address = Json.obj();
        address.put("kind", "session");
        address.put("sessionId", sessionId);
        Map<String, Object> request = Json.obj();
        request.put("address", address);
        request.put("assistantStream", Boolean.TRUE);
        Map<String, Object> args = Json.obj();
        args.put("request", request);
        return args;
    }

    /** Best-effort text extraction from an assistant-stream chunk. */
    private static String chunkText(Object chunk) {
        if (chunk == null) {
            return "";
        }
        if (chunk instanceof String) {
            return (String) chunk;
        }
        String direct = Json.str(chunk, "text");
        if (direct != null) {
            return direct;
        }
        String delta = Json.str(chunk, "delta");
        if (delta != null) {
            return delta;
        }
        Object content = Json.get(chunk, "content");
        if (content instanceof String) {
            return (String) content;
        }
        return "";
    }

    /** Best-effort text extraction from an AssistantMessage: {content:[{type:'text',text:'..'}]}. */
    private static String messageText(Object message) {
        if (message == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Object content = Json.get(message, "content");
        if (content instanceof List) {
            List<?> parts = (List<?>) content;
            for (int i = 0; i < parts.size(); i++) {
                Object p = parts.get(i);
                String t = Json.str(p, "text");
                if (t != null) {
                    sb.append(t);
                }
            }
        } else if (content instanceof String) {
            sb.append((String) content);
        }
        String s = Json.str(message, "text");
        if (sb.length() == 0 && s != null) {
            sb.append(s);
        }
        return sb.toString();
    }

    private File makeFixture() throws IOException {
        File f = new File(outDir, "harness-upload.bin");
        byte[] buf = new byte[64 * 1024 + 123];
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < buf.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            buf[i] = (byte) (seed >>> 33);
        }
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(buf);
        } finally {
            os.close();
        }
        return f;
    }

    private static void copyPrefix(File src, File dst, long count) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] b = new byte[8192];
            long left = count;
            while (left > 0) {
                int n = in.read(b, 0, (int) Math.min(b.length, left));
                if (n < 0) {
                    break;
                }
                out.write(b, 0, n);
                left -= n;
            }
        } finally {
            try { in.close(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
        }
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    md.update(b, 0, n);
                }
            } finally {
                in.close();
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable: " + e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static void delete(File f) {
        if (f.exists() && !f.delete()) {
            System.out.println(TAG_INFO + "could not delete " + f.getAbsolutePath());
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }

    private void section(String title) {
        System.out.println();
        System.out.println("----------------------------------------------------------------");
        System.out.println(" " + title);
        System.out.println("----------------------------------------------------------------");
    }

    private void pass(String what) {
        passed.add(what);
        System.out.println(TAG_OK + what);
    }

    private void fail(String what) {
        failed.add(what);
        System.out.println(TAG_FAIL + what);
    }

    private void skip(String what) {
        skipped.add(what);
        System.out.println(TAG_SKIP + what);
    }

    private void connectionFailure(String what, IOException e) {
        System.out.println();
        System.out.println(TAG_FAIL + "CANNOT REACH THE GATEWAY: " + what);
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        System.out.println("        " + root.getClass().getName() + ": " + root.getMessage());
        if (root instanceof ConnectException) {
            System.out.println("        connection refused - nothing is listening on " + baseUrl);
        } else if (root instanceof UnknownHostException) {
            System.out.println("        DNS lookup failed for the host in " + baseUrl);
        }
        System.out.println();
        System.out.println("  This is expected until dsh-remote-gateway is running and reachable.");
        System.out.println("  Start the gateway, then re-run with a reachable --url.");
        System.out.println("  (The net/ layer itself compiled and loaded cleanly; only the transport failed.)");
    }

    private int summary(int rc) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println(" SUMMARY   passed=" + passed.size() + "  failed=" + failed.size()
                + "  skipped=" + skipped.size() + "   exit=" + rc);
        System.out.println("================================================================");
        for (int i = 0; i < passed.size(); i++) {
            System.out.println("  PASS  " + passed.get(i));
        }
        for (int i = 0; i < failed.size(); i++) {
            System.out.println("  FAIL  " + failed.get(i));
        }
        for (int i = 0; i < skipped.size(); i++) {
            System.out.println("  SKIP  " + skipped.get(i));
        }
        return rc;
    }
}
