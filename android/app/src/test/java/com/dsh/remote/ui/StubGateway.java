package com.dsh.remote.ui;

import com.dsh.remote.net.Json;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A loopback HTTP/1.1 server for the UI tests.
 *
 * <p>This exists so the Activities can be driven <em>without a seam</em>: they construct
 * {@code GatewayClient}/{@code DshRpcClient} internally from {@link App}'s stored base URL,
 * so the tests point that stored URL at this server and let the REAL
 * {@code net/} stack ({@code Http} → {@code HttpURLConnection} → real socket) run.
 * Nothing in {@code java/com/dsh/remote/} is stubbed or mocked.
 *
 * <p>It is deliberately tiny: a request line, headers, a Content-Length body, and a
 * hand-written response. It can also answer a WebSocket upgrade (RFC 6455 handshake with a
 * correct {@code Sec-WebSocket-Accept}) and then keep the socket open, which is all
 * {@code ChatActivity} needs to reach its "streams open" state.
 *
 * <p>This is a test double. It is NOT the gateway and proves nothing about the gateway.
 */
final class StubGateway implements Closeable {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // ------------------------------------------------------------------ model

    static final class Request {
        final String method;
        final String target;           // path + query, as sent
        final String path;             // path only
        final String query;
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        final byte[] body;

        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            int q = target.indexOf('?');
            this.path = q < 0 ? target : target.substring(0, q);
            this.query = q < 0 ? "" : target.substring(q + 1);
            this.headers.putAll(headers);
            this.body = body == null ? new byte[0] : body;
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        String bodyText() {
            return new String(body, UTF8);
        }

        Object bodyJson() {
            return Json.parse(bodyText());
        }

        /** Value of a query parameter, or "" when absent. */
        String param(String name) {
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                String k = i < 0 ? pair : pair.substring(0, i);
                if (k.equals(name)) {
                    return i < 0 ? "" : urlDecode(pair.substring(i + 1));
                }
            }
            return "";
        }

        @Override
        public String toString() {
            return method + " " + target;
        }
    }

    static final class Response {
        int status = 200;
        String reason = "OK";
        final Map<String, String> headers = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];
        /** When true the server has already written the response and keeps the socket open. */
        boolean upgraded;
        /** When true the server keeps the socket open after writing (idle WebSocket). */
        boolean keepOpen;
        /**
         * When true the server closes the socket without writing any response at all, which is
         * what makes HttpURLConnection raise an IOException.
         */
        boolean dropConnection;

        static Response json(Object value) {
            return text(200, "OK", Json.stringify(value), "application/json");
        }

        static Response text(int status, String reason, String body, String contentType) {
            Response r = new Response();
            r.status = status;
            r.reason = reason;
            r.body = body.getBytes(UTF8);
            r.headers.put("Content-Type", contentType + "; charset=utf-8");
            return r;
        }

        static Response status(int status, String reason) {
            Response r = new Response();
            r.status = status;
            r.reason = reason;
            r.headers.put("Content-Type", "application/json; charset=utf-8");
            r.body = ("{\"ok\":false,\"error\":\"http_" + status + "\",\"message\":\""
                    + reason + "\"}").getBytes(UTF8);
            return r;
        }

        static Response bytes(byte[] data, String contentType) {
            Response r = new Response();
            r.body = data;
            r.headers.put("Content-Type", contentType);
            return r;
        }

        /** Closes the connection with no response at all (a dropped-mid-request failure). */
        static Response dropConnection() {
            Response r = new Response();
            r.dropConnection = true;
            return r;
        }
    }

    /**
     * One request plus the socket it arrived on. The output stream is exposed because a
     * WebSocket upgrade has to be written on that exact socket.
     */
    static final class Exchange {
        final Request request;
        final OutputStream out;

        Exchange(Request request, OutputStream out) {
            this.request = request;
            this.out = out;
        }

        Request req() {
            return request;
        }

        /** Answers this request with an RFC 6455 upgrade, then pushes {@code frames}. */
        Response upgrade(String... frames) throws IOException {
            return StubGateway.upgrade(request, out, frames);
        }

        /** A sink for frames that must be sent AFTER the client's mux {@code open} frames. */
        FrameSink sink() {
            return new FrameSink(out);
        }
    }

    /**
     * Writes server WebSocket text frames at an arbitrary later moment.
     *
     * <p>{@code ChatActivity} opens {@code session/follow} and {@code $events} as separate
     * {@code open} frames on the mux socket. Frames pushed during the handshake would arrive
     * before either stream exists and {@code DshRpcClient} would drop them, so tests queue
     * their frames here and {@link StubGateway} flushes them once both {@code open} frames
     * have been seen.
     */
    static final class FrameSink {
        private final OutputStream out;

        FrameSink(OutputStream out) {
            this.out = out;
        }

        synchronized void send(String... frames) throws IOException {
            for (String f : frames) {
                out.write(serverTextFrame(f));
            }
            out.flush();
        }
    }

    interface Handler {
        Response handle(Exchange ex) throws Exception;
    }

    // ------------------------------------------------------------------ state

    private final ServerSocket serverSocket;
    private final ExecutorService workers;
    private final List<Request> recorded = Collections.synchronizedList(new ArrayList<Request>());
    private final List<byte[]> rawBodies = Collections.synchronizedList(new ArrayList<byte[]>());
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile Handler handler;
    private volatile int keepAliveSockets = 0;

    StubGateway() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-gateway");
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, "stub-gateway-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    void setHandler(Handler h) {
        this.handler = h;
    }

    /** Snapshot of every request received so far, in arrival order. */
    List<Request> requests() {
        synchronized (recorded) {
            return new ArrayList<Request>(recorded);
        }
    }

    int requestCount() {
        return recorded.size();
    }

    Request request(int index) {
        synchronized (recorded) {
            return recorded.get(index);
        }
    }

    byte[] rawBody(int index) {
        synchronized (rawBodies) {
            return rawBodies.get(index);
        }
    }

    List<Request> requestsFor(String path) {
        List<Request> out = new ArrayList<Request>();
        for (Request r : requests()) {
            if (r.path.equals(path)) {
                out.add(r);
            }
        }
        return out;
    }

    /** How many WebSocket upgrades were accepted (i.e. live mux sockets opened). */
    int upgradeCount() {
        return upgrades.get();
    }

    private final AtomicInteger upgrades = new AtomicInteger();

    // ------------------------------------------------------------------ plumbing

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            final Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                return; // closed
            }
            workers.execute(() -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        boolean keep = false;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(20000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            int index = sequence.getAndIncrement();
            Request req = readRequest(in);
            if (req == null) {
                return;
            }
            synchronized (recorded) {
                recorded.add(req);
                rawBodies.add(req.body);
            }
            Handler h = handler;
            Response resp = h == null
                    ? Response.status(404, "no stub handler installed")
                    : h.handle(new Exchange(req, out));
            if (resp == null) {
                resp = Response.status(404, "stub returned null for " + req.target);
            }
            if (resp.upgraded) {
                // the handler already wrote the 101 and any frames it wanted
                keep = resp.keepOpen;
                upgrades.incrementAndGet();
                if (keep) {
                    holdOpen(socket, out);
                }
                return;
            }
            if (resp.dropConnection) {
                // no response bytes at all: the client sees the peer vanish
                socket.close();
                return;
            }
            writeResponse(out, resp, req);
            out.flush();
            if (resp.keepOpen) {
                holdOpen(socket, out);
            }
        } catch (Exception e) {
            // a test double must never take the test down with it
            System.out.println("[StubGateway] connection error: " + e);
        } finally {
            if (!keep) {
                try {
                    socket.close();
                } catch (IOException ignore) {
                    // nothing to do
                }
            }
        }
    }

    /**
     * Reads client WebSocket frames until the peer closes, so the client sees an
     * open-but-quiet WebSocket. Once both mux streams have been opened by the client, any
     * frames registered via {@link #queueFrames} are flushed (see {@link FrameSink}).
     */
    private void holdOpen(Socket socket, OutputStream out) {
        keepAliveSockets++;
        try {
            InputStream in = socket.getInputStream();
            boolean followOpened = false;
            boolean eventsOpened = false;
            while (true) {
                String payload = readClientFrame(in);
                if (payload == null) {
                    break;
                }
                if (payload.contains("\"session/follow\"")) {
                    followOpened = true;
                }
                if (payload.contains("\"$events\"")) {
                    eventsOpened = true;
                }
                if (followOpened && eventsOpened && queuedSink != null && queuedFrames != null) {
                    queuedSink.send(queuedFrames);
                    followOpened = false;
                    eventsOpened = false;
                    queuedSink = null;
                    queuedFrames = null;
                }
            }
        } catch (IOException ignore) {
            // peer closed or timed out
        } finally {
            keepAliveSockets--;
            try {
                socket.close();
            } catch (IOException ignore) {
                // nothing to do
            }
        }
    }

    /** Reads one (masked) client frame and returns its text payload, or null at EOF. */
    private static String readClientFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        if (b1 < 0) {
            return null;
        }
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }
        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask);
        }
        if (len > 1 << 20) {
            return null; // never happens in these tests; refuse rather than allocate
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        if (opcode == 0x8) {
            return null; // close
        }
        if (opcode == 0x1) {
            return new String(payload, UTF8);
        }
        return ""; // ping/pong/binary: irrelevant to these tests
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("eof");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("eof");
            }
            off += n;
        }
    }

    // ------------------------------------------------------------------ queued frames

    private volatile FrameSink queuedSink;
    private volatile String[] queuedFrames;

    /**
     * Registers frames to be delivered as soon as the client opens both {@code session/follow}
     * and {@code $events} on the mux socket.
     */
    void queueFrames(FrameSink sink, String... frames) {
        this.queuedSink = sink;
        this.queuedFrames = frames;
    }

    private static Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) {
                headers.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
        }
        byte[] body = new byte[0];
        String te = headerOf(headers, "Transfer-Encoding");
        String cl = headerOf(headers, "Content-Length");
        if (te != null && te.toLowerCase().contains("chunked")) {
            body = readChunked(in);
        } else if (cl != null) {
            int n = Integer.parseInt(cl.trim());
            body = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(body, off, n - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
        }
        return new Request(parts[0], parts[1], headers, body);
    }

    private static String headerOf(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) {
                sizeLine = sizeLine.substring(0, semi);
            }
            int size = Integer.parseInt(sizeLine.trim(), 16);
            if (size == 0) {
                readLine(in); // trailing CRLF
                break;
            }
            byte[] chunk = new byte[size];
            int off = 0;
            while (off < size) {
                int r = in.read(chunk, off, size - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            out.write(chunk, 0, off);
            readLine(in);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
        }
        if (c < 0 && buf.size() == 0) {
            return null;
        }
        return new String(buf.toByteArray(), UTF8);
    }

    private static void writeResponse(OutputStream out, Response resp, Request req) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(resp.reason).append("\r\n");
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(resp.body.length).append("\r\n");
        sb.append("Connection: ").append(resp.keepOpen ? "keep-alive" : "close").append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(UTF8));
        if (resp.body.length > 0) {
            out.write(resp.body);
        }
    }

    // ------------------------------------------------------------------ websocket

    /**
     * Answers an RFC 6455 upgrade with a correct {@code Sec-WebSocket-Accept} and then holds
     * the socket open. Any {@code framesToSend} are written immediately after the handshake.
     */
    static Response upgrade(Request req, OutputStream out, String... framesToSend) throws IOException {
        String key = req.header("Sec-WebSocket-Key");
        if (key == null) {
            return Response.status(400, "missing Sec-WebSocket-Key");
        }
        String accept = base64Sha1(key + WS_MAGIC);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Accept: ").append(accept).append("\r\n\r\n");
        out.write(sb.toString().getBytes(UTF8));
        for (String frame : framesToSend) {
            out.write(serverTextFrame(frame));
        }
        out.flush();
        Response r = new Response();
        r.upgraded = true;
        r.keepOpen = true;
        return r;
    }

    /** A server-to-client text frame (FIN=1, unmasked — which is what RFC 6455 requires). */
    static byte[] serverTextFrame(String text) {
        byte[] payload = text.getBytes(UTF8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x81);
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65536) {
            out.write(126);
            out.write((payload.length >>> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) payload.length >>> (8 * i)) & 0xFF));
            }
        }
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static String base64Sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(UTF8));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ json helpers

    /** Builds {@code {"type":"item","streamId":…,"value":…}} — a mux item frame. */
    static String muxItem(String streamId, Object value) {
        Map<String, Object> m = Json.obj();
        m.put("type", "item");
        m.put("streamId", streamId);
        m.put("value", value);
        return Json.stringify(m);
    }

    /** Builds a {@code session/follow} {@code assistant-stream} chunk frame. */
    static Map<String, Object> assistantChunk(String text) {
        Map<String, Object> chunk = Json.obj();
        chunk.put("type", "text-delta");
        chunk.put("text", text);
        Map<String, Object> frame = Json.obj();
        frame.put("type", "chunk");
        frame.put("chunk", chunk);
        Map<String, Object> value = Json.obj();
        value.put("type", "assistant-stream");
        value.put("frame", frame);
        return value;
    }

    /** Builds a durable {@code event} frame for {@code session/follow}. */
    static Map<String, Object> followEvent(String type, Map<String, Object> data) {
        Map<String, Object> event = Json.obj();
        event.put("type", type);
        event.put("data", data);
        Map<String, Object> value = Json.obj();
        value.put("type", "event");
        value.put("event", event);
        return value;
    }

    /** Builds a {@code $events} {@code waterfall} frame asking for a tool approval. */
    static Map<String, Object> approvalWaterfall(String eventId, String toolName, String reason) {
        Map<String, Object> request = Json.obj();
        request.put("toolName", toolName);
        request.put("reason", reason);
        Map<String, Object> value = Json.obj();
        value.put("type", "waterfall");
        value.put("event", "approval/request");
        value.put("eventId", eventId);
        value.put("request", request);
        return value;
    }

    /** Builds the {@code $events} {@code ready} frame. */
    static Map<String, Object> eventsReady(String clientId) {
        Map<String, Object> value = Json.obj();
        value.put("type", "ready");
        value.put("clientId", clientId);
        return value;
    }

    /** Builds a successful {@code /api/<ns>/<method>} reply echoing the request's rpcId. */
    static Response rpcOk(Request req, Object value) {
        Object envelope;
        try {
            envelope = req.bodyJson();
        } catch (RuntimeException e) {
            return Response.status(400, "unparseable RPC body");
        }
        Map<String, Object> result = Json.obj();
        result.put("ok", true);
        result.put("value", value);
        Map<String, Object> reply = Json.obj();
        reply.put("type", "server-response");
        reply.put("rpcId", Json.str(envelope, "rpcId"));
        reply.put("result", result);
        return Response.json(reply);
    }

    /** Builds a business-error reply ({@code result.ok == false}) echoing the rpcId. */
    static Response rpcError(Request req, String code, String message) {
        Object envelope = req.bodyJson();
        Map<String, Object> error = Json.obj();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> result = Json.obj();
        result.put("ok", false);
        result.put("error", error);
        Map<String, Object> reply = Json.obj();
        reply.put("type", "server-response");
        reply.put("rpcId", Json.str(envelope, "rpcId"));
        reply.put("result", result);
        return Response.json(reply);
    }

    /** The {@code method} field of an RPC request body (e.g. {@code "session/list"}). */
    static String rpcMethod(Request req) {
        try {
            return Json.str(req.bodyJson(), "method");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code payload.args} of an RPC request body. */
    static Map<String, Object> rpcArgs(Request req) {
        try {
            Object args = Json.get(req.bodyJson(), "payload", "args");
            return args instanceof Map ? Json.map(req.bodyJson(), "payload", "args") : Json.obj();
        } catch (RuntimeException e) {
            return Json.obj();
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignore) {
            // nothing to do
        }
        workers.shutdownNow();
    }
}
