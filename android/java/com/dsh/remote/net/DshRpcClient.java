package com.dsh.remote.net;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the dsh browser-transport RPC protocol (dsh-transport-spec.md):
 * unary JSON-RPC over HTTP POST at /api/&lt;ns&gt;/&lt;method&gt;, plus one multiplexed
 * WebSocket at /api/remote.mux for all streaming endpoints and the $events firehose.
 *
 * <p>The {@link Http} instance is shared (base URL + Bearer token), so login state and
 * the bearer used for the WebSocket handshake stay in one place.
 */
public final class DshRpcClient {

    private static final String MUX_PATH = "/api/remote.mux";

    private final Http http;
    private final Mux mux = new Mux();

    /** Shares the Http instance (base URL + Bearer token). */
    public DshRpcClient(Http http) {
        if (http == null) throw new NullPointerException("http == null");
        this.http = http;
    }

    /**
     * POST /api/&lt;ns&gt;/&lt;method&gt; with
     * {@code {"type":"client-request","rpcId":"<uuid4>","method":"<ns>/<method>","payload":{"args":<args>}}}.
     * Validates the echoed rpcId, then {@code result.ok==true} returns {@code result.value},
     * {@code result.ok==false} throws {@link DshException}. Non-2xx throws
     * {@code DshException("http/<status>", body, null)}. {@code args==null} sends {"args":{}}.
     */
    public Object call(String nsMethod, Map<String, Object> args) throws IOException {
        if (nsMethod == null) throw new NullPointerException("nsMethod == null");
        String method = nsMethod.startsWith("/") ? nsMethod.substring(1) : nsMethod;
        String rpcId = Uuid.v4();

        Map<String, Object> payload = Json.obj();
        payload.put("args", args == null ? Json.obj() : args);

        Map<String, Object> envelope = Json.obj();
        envelope.put("type", "client-request");
        envelope.put("rpcId", rpcId);
        envelope.put("method", method);
        envelope.put("payload", payload);

        Http.Response resp = http.postJson("/api/" + method, Json.stringify(envelope));
        if (!resp.ok()) {
            String body = resp.body == null ? "" : resp.body;
            if (body.length() > 500) body = body.substring(0, 500);
            throw new DshException("http/" + resp.status, body, null);
        }

        Object j = resp.json();
        String type = Json.str(j, "type");
        String echoed = Json.str(j, "rpcId");
        if (!"server-response".equals(type) || !rpcId.equals(echoed)) {
            throw new DshException("rpc-id-mismatch",
                    "sent rpcId " + rpcId + " but reply has type=" + type + " rpcId=" + echoed, j);
        }

        Object result = Json.get(j, "result");
        if (result instanceof Map) {
            boolean ok = Json.bool(result, false, "ok");
            if (ok) return Json.get(result, "value");
            Object error = Json.get(result, "error");
            String code = Json.str(error, "code");
            String message = Json.str(error, "message");
            Object details = error == null ? null : Json.get(error, "details");
            if (code == null) code = "rpc/error";
            if (message == null) message = "RPC failed";
            throw new DshException(code, message, details);
        }
        throw new DshException("bad-response", "server-response is missing result", j);
    }

    public interface StreamListener {
        void onValue(Object value); // the mux frame `value`
        void onError(Object error); // {"code":…,"message":…,"details":…}
        void onEnd();
    }

    public Mux mux() {
        return mux;
    }

    // ------------------------------------------------------------------
    // Convenience wrappers (exact wire shapes from dsh-transport-spec.md)
    // ------------------------------------------------------------------

    /** session/list, args {"_request":{}} (UNDERSCORE) -> value.items, never null. */
    public List<Object> sessionList() throws IOException {
        Map<String, Object> args = Json.obj();
        args.put("_request", Json.obj());
        Object value = call("session/list", args);
        return Json.list(value, "items");
    }

    /** session/create, args {"request":{...}}. cwd==null omits the key. Returns value.sessionId. */
    public String sessionCreate(String cwd) throws IOException {
        Map<String, Object> request = Json.obj();
        if (cwd != null) request.put("cwd", cwd);
        Map<String, Object> args = Json.obj();
        args.put("request", request);
        Object value = call("session/create", args);
        return Json.str(value, "sessionId");
    }

    /** session/prompt, args {"request":{"requestId":uuid,"sessionId":sid,"mode":"queue","content":[...]}}. */
    public void sessionPrompt(String sessionId, String text) throws IOException {
        Map<String, Object> request = Json.obj();
        request.put("requestId", Uuid.v4());
        request.put("sessionId", sessionId);
        request.put("mode", "queue");
        List<Object> content = Json.arr();
        Map<String, Object> item = Json.obj();
        item.put("type", "text");
        item.put("text", text == null ? "" : text);
        content.add(item);
        request.put("content", content);
        Map<String, Object> args = Json.obj();
        args.put("request", request);
        call("session/prompt", args);
    }

    /** session/cancel, args {"request":{"sessionId":sid}}. */
    public void sessionCancel(String sessionId) throws IOException {
        Map<String, Object> request = Json.obj();
        request.put("sessionId", sessionId);
        Map<String, Object> args = Json.obj();
        args.put("request", request);
        call("session/cancel", args);
    }

    /** $events/result, args {"clientId":cid,"eventId":eid,"outcome":outcome}. */
    public void eventsResult(String clientId, String eventId, Map<String, Object> outcome) throws IOException {
        Map<String, Object> args = Json.obj();
        args.put("clientId", clientId);
        args.put("eventId", eventId);
        args.put("outcome", outcome == null ? Json.obj() : outcome);
        call("$events/result", args);
    }

    /** Convenience: open a session/follow stream with assistantStream:true. */
    public void followSession(String streamId, String sessionId, StreamListener listener) throws IOException {
        Map<String, Object> address = Json.obj();
        address.put("kind", "session");
        address.put("sessionId", sessionId);
        Map<String, Object> request = Json.obj();
        request.put("address", address);
        request.put("assistantStream", true);
        Map<String, Object> args = Json.obj();
        args.put("request", request);
        mux.open(streamId, "session/follow", args, listener);
    }

    /** Convenience: open the $events stream. */
    public void openEvents(String streamId, StreamListener listener) throws IOException {
        mux.open(streamId, "$events", Json.obj(), listener);
    }

    // ------------------------------------------------------------------
    // Mux
    // ------------------------------------------------------------------

    /**
     * One shared WebSocket to &lt;base&gt;/api/remote.mux (http->ws, https->wss), opened
     * lazily on the first {@link #open}. All frames are TEXT; binary is refused by the
     * server with close 1003. Stream ids are caller-supplied and must be distinct.
     */
    public final class Mux {

        private final Object muxLock = new Object();
        private final Map<String, StreamListener> streams = new HashMap<String, StreamListener>();
        private WebSocketClient ws;

        /**
         * Open a logical stream. Callable from any thread. Connects the mux socket on the
         * first call; throws IOException if the connect or the open frame fails.
         */
        public void open(String streamId, String endpoint, Map<String, Object> args,
                         StreamListener listener) throws IOException {
            if (streamId == null) throw new NullPointerException("streamId == null");
            if (endpoint == null) throw new NullPointerException("endpoint == null");
            if (listener == null) throw new NullPointerException("listener == null");
            WebSocketClient sock;
            synchronized (muxLock) {
                if (streams.containsKey(streamId)) {
                    throw new IOException("stream already open: " + streamId);
                }
                sock = ensureConnected();
                streams.put(streamId, listener);
            }
            Map<String, Object> msg = Json.obj();
            msg.put("type", "open");
            msg.put("streamId", streamId);
            msg.put("endpoint", endpoint);
            Map<String, Object> payload = Json.obj();
            payload.put("args", args == null ? Json.obj() : args);
            msg.put("payload", payload);
            try {
                sock.sendText(Json.stringify(msg));
            } catch (IOException e) {
                synchronized (muxLock) {
                    streams.remove(streamId);
                }
                throw e;
            }
        }

        /** Outbound cancel; drops the local stream (a late end/error frame is ignored). */
        public void cancel(String streamId) {
            if (streamId == null) return;
            WebSocketClient sock;
            synchronized (muxLock) {
                streams.remove(streamId);
                sock = ws;
            }
            if (sock != null && sock.isOpen()) {
                Map<String, Object> msg = Json.obj();
                msg.put("type", "cancel");
                msg.put("streamId", streamId);
                try {
                    sock.sendText(Json.stringify(msg));
                } catch (IOException ignore) {
                    // mux is dying; stream failure is delivered by the socket close handler
                }
            }
        }

        /** Closes the mux socket and fails all live streams with mux/closed. */
        public void close() {
            WebSocketClient sock;
            synchronized (muxLock) {
                sock = ws;
                ws = null;
            }
            muxSocketDied("mux closed by client");
            if (sock != null) sock.close(1000, "client close");
        }

        public boolean isOpen() {
            synchronized (muxLock) {
                return ws != null && ws.isOpen();
            }
        }

        private WebSocketClient ensureConnected() throws IOException {
            if (ws != null && ws.isOpen()) return ws;
            String url = muxUrl();
            Map<String, String> hdrs = new HashMap<String, String>();
            String bearer = http.bearer();
            if (bearer != null && bearer.length() > 0) {
                hdrs.put("Authorization", "Bearer " + bearer);
            }
            final WebSocketClient[] holder = new WebSocketClient[1];
            WebSocketClient.Listener l = new WebSocketClient.Listener() {
                @Override
                public void onOpen() {
                }

                @Override
                public void onText(String message) {
                    Mux.this.onMuxText(message);
                }

                @Override
                public void onBinary(byte[] data) {
                }

                @Override
                public void onClose(int code, String reason) {
                    if (!Mux.this.isCurrentMux(holder[0])) return;
                    Mux.this.muxSocketDied(reason == null ? "mux connection closed"
                            : "mux connection closed: " + reason);
                }

                @Override
                public void onError(Throwable error) {
                    if (!Mux.this.isCurrentMux(holder[0])) return;
                    Mux.this.muxSocketDied(error == null ? "mux connection error"
                            : "mux connection error: " + error.getMessage());
                }
            };
            WebSocketClient created = new WebSocketClient(URI.create(url), hdrs, l);
            holder[0] = created;
            created.connect();
            ws = created;
            return created;
        }

        private boolean isCurrentMux(WebSocketClient candidate) {
            synchronized (muxLock) {
                return candidate != null && candidate == ws;
            }
        }

        private String muxUrl() throws IOException {
            String base = http.baseUrl();
            if (base == null || base.length() == 0) {
                throw new IOException("no base URL configured for the RPC client");
            }
            try {
                URI u = URI.create(base);
                String scheme = "https".equalsIgnoreCase(u.getScheme()) ? "wss" : "ws";
                StringBuilder sb = new StringBuilder();
                sb.append(scheme).append("://");
                String authority = u.getRawAuthority();
                if (authority == null || authority.length() == 0) {
                    throw new IOException("base URL has no authority: " + base);
                }
                sb.append(authority);
                String p = u.getRawPath();
                if (p == null || p.length() == 0 || "/".equals(p)) {
                    sb.append(MUX_PATH);
                } else {
                    if (!p.endsWith("/")) sb.append(p).append('/');
                    else sb.append(p);
                    sb.append("api/remote.mux");
                }
                return sb.toString();
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid base URL: " + base, e);
            }
        }

        private void onMuxText(String message) {
            Object parsed;
            try {
                parsed = Json.parse(message);
            } catch (RuntimeException e) {
                return; // not JSON; ignore
            }
            if (!(parsed instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) parsed;
            String type = Json.str(frame, "type");
            String sid = Json.str(frame, "streamId");
            if (sid == null) return; // no stream id; ignore
            if ("item".equals(type)) {
                StreamListener l;
                synchronized (muxLock) {
                    l = streams.get(sid);
                }
                if (l != null) {
                    try {
                        l.onValue(Json.get(frame, "value"));
                    } catch (Throwable ignore) {
                    }
                }
            } else if ("error".equals(type)) {
                StreamListener l;
                synchronized (muxLock) {
                    l = streams.remove(sid);
                }
                if (l != null) {
                    try {
                        l.onError(Json.get(frame, "error"));
                    } catch (Throwable ignore) {
                    }
                }
            } else if ("end".equals(type)) {
                StreamListener l;
                synchronized (muxLock) {
                    l = streams.remove(sid);
                }
                if (l != null) {
                    try {
                        l.onEnd();
                    } catch (Throwable ignore) {
                    }
                }
            }
            // Unknown frame types / stream ids are ignored, not fatal.
        }

        /** Socket died: every live stream gets onError({"code":"mux/closed",...}) once, then cleared. */
        private void muxSocketDied(String message) {
            Map<String, StreamListener> dead;
            synchronized (muxLock) {
                dead = streams;
                streams.clear();
            }
            if (dead.isEmpty()) return;
            Map<String, Object> err = Json.obj();
            err.put("code", "mux/closed");
            err.put("message", message == null ? "mux connection closed" : message);
            for (StreamListener l : dead.values()) {
                try {
                    l.onError(err);
                } catch (Throwable ignore) {
                }
            }
        }
    }
}