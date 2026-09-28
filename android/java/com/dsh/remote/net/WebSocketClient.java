package com.dsh.remote.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal RFC 6455 WebSocket client over {@link Socket}/{@link SSLSocket},
 * pure Java (no android.*, no external HTTP libs).
 *
 * <p>Threading: one daemon reader thread started by {@link #connect()}, one daemon
 * keepalive thread (optional), all writes synchronised on a private lock. Client frames
 * are always masked with a fresh 4-byte {@link SecureRandom} mask; a masked server frame
 * is a protocol error.
 *
 * <p>This client answers every server Ping with an immediate Pong carrying the same
 * payload (dsh heartbeats every 2000 ms and terminates a silent peer).
 */
public final class WebSocketClient {

    public interface Listener {
        void onOpen();
        void onText(String message);
        /** Optional; may be left as a no-op body. */
        void onBinary(byte[] data);
        void onClose(int code, String reason);
        void onError(Throwable error);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int MAX_HEADER_LINE = 16384;
    private static final int MAX_ERROR_BODY = 65536;
    /** Defensive cap so a hostile length prefix cannot OOM the process. */
    private static final long MAX_FRAME_BYTES = 64L * 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final URI uri;
    private final Map<String, String> headers;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final Object termLock = new Object();
    private final Object pongLock = new Object();
    /** Exactly-once terminal callback guard: onClose and onError can never both fire. */
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile boolean open = false;
    private volatile String closeReason = null;
    private volatile int readTimeoutMs = 0;
    private volatile int clientPingSeconds = 20;
    private volatile long lastWriteMillis = System.currentTimeMillis();
    private volatile long lastPongMillis = System.currentTimeMillis();

    private Thread readerThread;
    private Thread keepAliveThread;

    // Fragmentation state, touched only by the reader thread.
    private boolean inFragment = false;
    private int fragmentOpcode = 0;
    private ByteArrayOutputStream fragment;

    public WebSocketClient(URI uri, Map<String, String> headers, Listener listener) {
        if (uri == null) throw new NullPointerException("uri == null");
        if (listener == null) throw new NullPointerException("listener == null");
        this.uri = uri;
        this.headers = headers == null ? new HashMap<String, String>() : new HashMap<String, String>(headers);
        this.listener = listener;
    }

    /**
     * Blocking: TCP connect (ws/wss from the URI scheme), HTTP upgrade handshake,
     * verify 101 + {@code Sec-WebSocket-Accept}, then start the reader thread.
     *
     * @throws IOException on any failure; a non-101 response yields an IOException whose
     *                     message contains the server's status line and any body.
     */
    public void connect() throws IOException {
        if (listener == null) throw new NullPointerException("listener == null");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean secure = "wss".equals(scheme);
        if (!"ws".equals(scheme) && !secure) {
            throw new IOException("unsupported WebSocket scheme: " + uri.getScheme());
        }
        String host = uri.getHost();
        if (host == null || host.length() == 0) {
            throw new IOException("WebSocket URI has no host: " + uri);
        }
        int port = uri.getPort();
        if (port < 0) port = secure ? 443 : 80;

        Socket s = null;
        try {
            if (secure) {
                s = createSslSocket(host, port);
            } else {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(readTimeoutMs);
            this.socket = s;
            this.in = s.getInputStream();
            this.out = s.getOutputStream();
            performHandshake();
            open = true;
            startReader();
            startKeepAliveIfEnabled();
            try {
                listener.onOpen();
            } catch (Throwable ignore) {
                // listener bugs must not prevent connect() from returning
            }
        } catch (IOException e) {
            closeSocketQuietly(s);
            throw e;
        } catch (RuntimeException e) {
            closeSocketQuietly(s);
            throw e;
        }
    }

    /** Thread-safe. Sends one masked text frame (FIN=1). */
    public void sendText(String message) throws IOException {
        byte[] payload = (message == null ? "" : message).getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            if (!open || out == null) throw new IOException("WebSocket is not open");
            writeFrame(0x1, payload);
            out.flush();
        }
    }

    /** Thread-safe. Sends a masked Ping control frame. */
    public void sendPing(byte[] payload) throws IOException {
        byte[] p = payload == null ? new byte[0] : payload;
        if (p.length > 125) throw new IOException("ping payload exceeds 125 bytes");
        synchronized (writeLock) {
            if (!open || out == null) throw new IOException("WebSocket is not open");
            writeFrame(0x9, p);
            out.flush();
        }
    }

    /** Thread-safe, idempotent. Sends a masked Close frame (code 1000 default) then closes the socket. */
    public void close(int code, String reason) {
        boolean fire = false;
        int fcode = code;
        String freason = reason == null ? "" : reason;
        synchronized (termLock) {
            if (!open) return;
            synchronized (writeLock) {
                if (out != null) {
                    try {
                        writeFrame(0x8, closePayload(fcode, freason));
                        out.flush();
                    } catch (IOException ignore) {
                        // socket already dying; fall through to termination
                    }
                }
            }
            closeReason = fcode + " " + freason;
            closeSocket();
            open = false;
            notifyKeepAlive();
            fire = true;
        }
        if (fire) fireClose(fcode, freason);
    }

    public boolean isOpen() {
        return open;
    }

    /** Last close code/reason, for diagnostics; null until the first close. */
    public String closeReason() {
        return closeReason;
    }

    /**
     * Socket {@code SO_TIMEOUT} for the reader, default 0 (blocking). A non-zero value is
     * treated as a liveness signal: {@link SocketTimeoutException} is not fatal.
     */
    public void setReadTimeoutMs(int ms) {
        this.readTimeoutMs = ms;
        Socket s = socket;
        if (s != null) {
            try {
                s.setSoTimeout(ms);
            } catch (IOException ignore) {
                // socket already closed
            }
        }
    }

    /** Client-initiated keepalive: Ping every N seconds of write idleness (default 20; 0 disables). */
    public void setClientPingSeconds(int seconds) {
        this.clientPingSeconds = seconds;
        if (seconds <= 0) return;
        synchronized (termLock) {
            if (open && keepAliveThread == null) startKeepAliveIfEnabled();
        }
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private void performHandshake() throws IOException {
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.length() == 0) rawPath = "/";
        String rawQuery = uri.getRawQuery();
        String target = (rawQuery != null && rawQuery.length() > 0) ? rawPath + "?" + rawQuery : rawPath;

        String host = uri.getHost();
        int port = uri.getPort();
        boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        StringBuilder hostHeader = new StringBuilder(host == null ? "" : host);
        if (port >= 0 && !((secure && port == 443) || (!secure && port == 80))) {
            hostHeader.append(':').append(port);
        }

        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);

        StringBuilder req = new StringBuilder(256);
        req.append("GET ").append(target).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHeader).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k != null && v != null) {
                req.append(k).append(": ").append(v).append("\r\n");
            }
        }
        req.append("\r\n");

        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null) {
            throw new IOException("WebSocket upgrade failed: no HTTP response (connection closed)");
        }
        int status = 0;
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2) {
            try {
                status = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignore) {
                // fall through; a garbage status line is a non-101 failure below
            }
        }
        if (status != 101) {
            Map<String, String> respHeaders = readHeaders();
            String body = readErrorBody(respHeaders);
            String msg = "WebSocket upgrade failed: " + statusLine;
            if (body != null && body.length() > 0) {
                String b = body.length() > 300 ? body.substring(0, 300) + "..." : body;
                msg += " | body: " + b;
            }
            throw new IOException(msg);
        }
        Map<String, String> respHeaders = readHeaders();
        String acceptHeader = header(respHeaders, "Sec-WebSocket-Accept");
        String expected = expectedAccept(key);
        if (acceptHeader == null || !expected.equals(acceptHeader.trim())) {
            throw new IOException("WebSocket upgrade failed: Sec-WebSocket-Accept mismatch "
                    + "(expected " + expected + ", got " + acceptHeader + ")");
        }
    }

    private SSLSocket createSslSocket(String host, int port) throws IOException {
        if (Http.insecureTls()) {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[] { TRUST_ALL }, RANDOM);
                SSLSocket ssl = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
                // Raw SSLSocket has no hostname-verifier setter; hostname checking on a raw
                // SSLSocket only happens when endpoint identification is enabled, so leave it
                // disabled (the permissive equivalent).
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm(null);
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                return ssl;
            } catch (GeneralSecurityException e) {
                throw new IOException("failed to initialise TLS for wss://" + host + ":" + port, e);
            }
        }
        SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        ssl.startHandshake();
        return ssl;
    }

    private static String expectedAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // ------------------------------------------------------------------
    // Reader
    // ------------------------------------------------------------------

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "dsh-ws-reader");
        t.setDaemon(true);
        readerThread = t;
        t.start();
    }

    private void readLoop() {
        boolean closeHandled = false;
        try {
            while (open) {
                int b0;
                try {
                    b0 = in.read();
                } catch (SocketTimeoutException timeout) {
                    continue; // SO_TIMEOUT is a liveness signal, not fatal
                }
                if (b0 < 0) break; // EOF
                if (!handleFrame(b0)) {
                    closeHandled = true;
                    break;
                }
            }
        } catch (ProtocolException e) {
            closeSocket();
            open = false;
            notifyKeepAlive();
            fireError(e);
            return;
        } catch (IOException e) {
            closeSocket();
            open = false;
            notifyKeepAlive();
            fireClose(1006, "connection error: " + e.getMessage());
            return;
        }
        closeSocket();
        open = false;
        notifyKeepAlive();
        if (!closeHandled) fireClose(1006, "connection closed");
    }

    /** @return false when the connection should stop (close frame processed). */
    private boolean handleFrame(int b0) throws IOException {
        int b1 = in.read();
        if (b1 < 0) throw new EOFException("WebSocket connection closed mid-frame");
        boolean fin = (b0 & 0x80) != 0;
        int rsv = (b0 >> 4) & 0x07;
        int opcode = b0 & 0x0F;
        if (rsv != 0) {
            throw new ProtocolException("WebSocket protocol error: RSV bits set (rsv=" + rsv + ")");
        }
        boolean masked = (b1 & 0x80) != 0;
        if (masked) {
            throw new ProtocolException("WebSocket protocol error: masked server frame");
        }
        long len = b1 & 0x7F;
        if (len == 126) {
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException("WebSocket connection closed mid-frame");
            len = ((hi & 0xFF) << 8) | (lo & 0xFF);
        } else if (len == 127) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                int c = in.read();
                if (c < 0) throw new EOFException("WebSocket connection closed mid-frame");
                v = (v << 8) | (c & 0xFF);
            }
            len = v;
        }
        if (len > MAX_FRAME_BYTES) {
            throw new ProtocolException("WebSocket protocol error: frame too large (" + len + ")");
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);

        if (opcode == 0x8 || opcode == 0x9 || opcode == 0xA) {
            if (!fin) throw new ProtocolException("WebSocket protocol error: fragmented control frame");
            if (len > 125) throw new ProtocolException("WebSocket protocol error: control frame too long");
        }

        switch (opcode) {
            case 0x0: // continuation
                if (!inFragment) {
                    throw new ProtocolException("WebSocket protocol error: continuation without a started message");
                }
                fragment.write(payload, 0, payload.length);
                if (fin) {
                    byte[] full = fragment.toByteArray();
                    int fragOp = fragmentOpcode;
                    fragment.reset();
                    inFragment = false;
                    fragmentOpcode = 0;
                    deliverData(fragOp, full);
                }
                return true;
            case 0x1: // text
            case 0x2: // binary
                if (inFragment) {
                    throw new ProtocolException("WebSocket protocol error: new data frame during a fragmented message");
                }
                if (fin) {
                    deliverData(opcode, payload);
                } else {
                    inFragment = true;
                    fragmentOpcode = opcode;
                    fragment = new ByteArrayOutputStream(payload.length + 32);
                    fragment.write(payload, 0, payload.length);
                }
                return true;
            case 0x9: // ping -> immediate pong with the same payload
                sendPongInternal(payload);
                return true;
            case 0xA: // pong
                synchronized (pongLock) {
                    lastPongMillis = System.currentTimeMillis();
                    pongLock.notifyAll();
                }
                return true;
            case 0x8: // close
                handleCloseFrame(payload);
                return false;
            default:
                throw new ProtocolException("WebSocket protocol error: unknown opcode 0x"
                        + Integer.toHexString(opcode));
        }
    }

    private void deliverData(int opcode, byte[] payload) {
        if (opcode == 0x1) {
            String text = new String(payload, StandardCharsets.UTF_8);
            try {
                listener.onText(text);
            } catch (Throwable ignore) {
                // listener bug must not kill the read loop
            }
        } else {
            try {
                listener.onBinary(payload);
            } catch (Throwable ignore) {
            }
        }
    }

    private void handleCloseFrame(byte[] payload) {
        int code = 1005; // "no status received"
        String reason = "";
        if (payload.length >= 2) {
            code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            if (payload.length > 2) {
                reason = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
            }
        }
        byte[] echo = payload.length >= 2 ? new byte[] { payload[0], payload[1] } : new byte[0];
        synchronized (writeLock) {
            if (out != null) {
                try {
                    writeFrame(0x8, echo);
                    out.flush();
                } catch (IOException ignore) {
                    // peer already gone; terminate anyway
                }
            }
        }
        closeSocket();
        open = false;
        notifyKeepAlive();
        closeReason = code + " " + reason;
        fireClose(code, reason);
    }

    // ------------------------------------------------------------------
    // Frames / IO helpers
    // ------------------------------------------------------------------

    /** Every client frame is masked with a fresh 4-byte SecureRandom mask. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        int len = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
        frame.write(0x80 | opcode); // FIN=1
        if (len < 126) {
            frame.write(0x80 | len); // MASK=1
        } else if (len <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((len >>> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((len >>> (8 * i)) & 0xFF);
            }
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        frame.writeTo(out);
        lastWriteMillis = System.currentTimeMillis();
    }

    private void sendPongInternal(byte[] payload) {
        synchronized (writeLock) {
            if (open && out != null) {
                try {
                    writeFrame(0xA, payload);
                    out.flush();
                } catch (IOException ignore) {
                    // socket dying; the read loop will notice
                }
            }
        }
    }

    private byte[] closePayload(int code, String reason) {
        byte[] r = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[2 + r.length];
        p[0] = (byte) (code >>> 8);
        p[1] = (byte) code;
        if (r.length > 0) System.arraycopy(r, 0, p, 2, r.length);
        if (p.length > 125) {
            byte[] q = new byte[125];
            System.arraycopy(p, 0, q, 0, 125);
            p = q;
        }
        return p;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new EOFException("WebSocket connection closed mid-frame");
            off += r;
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        while (true) {
            int c = in.read();
            if (c < 0) {
                return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            if (c == '\n') {
                byte[] raw = buf.toByteArray();
                int end = raw.length;
                if (end > 0 && raw[end - 1] == '\r') end--;
                return new String(raw, 0, end, StandardCharsets.UTF_8);
            }
            buf.write(c);
            if (buf.size() > MAX_HEADER_LINE) {
                throw new IOException("WebSocket upgrade: response header line too long");
            }
        }
    }

    private Map<String, String> readHeaders() throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        while (true) {
            String line = readLine();
            if (line == null) throw new EOFException("WebSocket connection closed in response headers");
            if (line.length() == 0) break;
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (!h.containsKey(name)) h.put(name, value);
            }
        }
        return h;
    }

    private static String header(Map<String, String> headers, String name) {
        return headers == null ? null : headers.get(name.toLowerCase(Locale.ROOT));
    }

    private String readErrorBody(Map<String, String> headers) throws IOException {
        String cl = header(headers, "Content-Length");
        long len = -1;
        if (cl != null) {
            try {
                len = Long.parseLong(cl.trim());
            } catch (NumberFormatException ignore) {
            }
        }
        if (len > 0) {
            int n = (int) Math.min(len, MAX_ERROR_BODY);
            byte[] buf = new byte[n];
            readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
        // No Content-Length: best-effort read of whatever is already available
        ByteArrayOutputStream b = new ByteArrayOutputStream(256);
        if (in.available() > 0) {
            byte[] tmp = new byte[4096];
            int total = 0;
            while (total < MAX_ERROR_BODY) {
                int r = in.read(tmp);
                if (r < 0) break;
                b.write(tmp, 0, r);
                total += r;
            }
        }
        return b.size() > 0 ? new String(b.toByteArray(), StandardCharsets.UTF_8) : null;
    }

    // ------------------------------------------------------------------
    // Keepalive
    // ------------------------------------------------------------------

    private void startKeepAliveIfEnabled() {
        if (clientPingSeconds <= 0) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                keepAliveLoop();
            }
        }, "dsh-ws-keepalive");
        t.setDaemon(true);
        keepAliveThread = t;
        t.start();
    }

    private void keepAliveLoop() {
        while (open) {
            int interval = clientPingSeconds;
            if (interval <= 0) break;
            long idle = System.currentTimeMillis() - lastWriteMillis;
            long waitFor = interval * 1000L - idle;
            if (waitFor > 0) {
                synchronized (pongLock) {
                    try {
                        if (open) pongLock.wait(Math.min(waitFor, 1000L));
                    } catch (InterruptedException ignore) {
                        // re-check conditions at the top of the loop
                    }
                }
                continue;
            }
            // Idle for one full interval: send a Ping.
            long pongBefore = lastPongMillis;
            boolean sent;
            synchronized (writeLock) {
                if (!open || out == null) break;
                try {
                    writeFrame(0x9, new byte[0]);
                    out.flush();
                    sent = true;
                } catch (IOException e) {
                    sent = false;
                }
            }
            if (!sent) break;
            // Wait up to 2 intervals for the Pong; otherwise close with 1001.
            long deadline = System.currentTimeMillis() + 2L * interval * 1000L;
            synchronized (pongLock) {
                while (open && lastPongMillis == pongBefore) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) break;
                    try {
                        pongLock.wait(Math.min(remain, 500L));
                    } catch (InterruptedException ignore) {
                    }
                }
            }
            if (open && lastPongMillis == pongBefore) {
                close(1001, "keepalive timeout");
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Termination
    // ------------------------------------------------------------------

    private void fireClose(int code, String reason) {
        if (terminal.compareAndSet(false, true)) {
            try {
                listener.onClose(code, reason == null ? "" : reason);
            } catch (Throwable ignore) {
            }
        }
    }

    private void fireError(Throwable error) {
        if (terminal.compareAndSet(false, true)) {
            try {
                listener.onError(error);
            } catch (Throwable ignore) {
            }
        }
    }

    private void notifyKeepAlive() {
        synchronized (pongLock) {
            pongLock.notifyAll();
        }
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void closeSocketQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignore) {
            }
        }
    }
}