package com.dsh.remote.net;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Small HTTP client on {@link java.net.HttpURLConnection} / {@link HttpsURLConnection}.
 * Pure Java (no Android, OkHttp, Gson or java.net.http), so it also runs on a desktop JDK.
 *
 * <p>Error responses (non-2xx) are returned as ordinary {@link Response}s with the error
 * stream decoded, never thrown, matching the gateway contract's JSON error bodies.</p>
 */
public final class Http {

    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String ACCEPT_JSON = "application/json";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60000;
    private static final int STREAM_CHUNK = 64 * 1024;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] CRLF = "\r\n".getBytes(UTF8);
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private static final HostnameVerifier PERMISSIVE_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private static final X509TrustManager PERMISSIVE_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // trust all
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            // trust all
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static final SSLSocketFactory DEFAULT_SSL_SOCKET_FACTORY = HttpsURLConnection.getDefaultSSLSocketFactory();
    private static final HostnameVerifier DEFAULT_HOSTNAME_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier();

    private static volatile boolean insecureTls = false;
    private static volatile SSLSocketFactory insecureSslFactory;

    private final String baseUrl;
    private String bearer;
    private int connectTimeoutMs;
    private int readTimeoutMs;

    /** @param baseUrl e.g. "https://gw.example.com" or "http://127.0.0.1:3081"; trailing '/' is stripped. */
    public Http(String baseUrl) {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        String b = baseUrl.trim();
        while (b.length() > 0 && b.charAt(b.length() - 1) == '/') {
            b = b.substring(0, b.length() - 1);
        }
        this.baseUrl = b;
        this.bearer = null;
        this.connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** null/empty clears the token; otherwise "Authorization: Bearer &lt;t&gt;" is added to every request. */
    public void setBearer(String token) {
        this.bearer = (token == null || token.isEmpty()) ? null : token;
    }

    public String bearer() {
        return bearer;
    }

    public void setConnectTimeoutMs(int ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("connect timeout must be >= 0");
        }
        this.connectTimeoutMs = ms;
    }

    public void setReadTimeoutMs(int ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("read timeout must be >= 0");
        }
        this.readTimeoutMs = ms;
    }

    /**
     * Opt-in trust-all TLS (self-signed test gateways). Default false. Applies to
     * {@link HttpsURLConnection} usage here and is readable by the WebSocket client.
     */
    public static void setInsecureTls(boolean on) {
        insecureTls = on;
        try {
            if (on) {
                HttpsURLConnection.setDefaultSSLSocketFactory(insecureSslFactory());
                HttpsURLConnection.setDefaultHostnameVerifier(PERMISSIVE_HOSTNAME_VERIFIER);
            } else {
                HttpsURLConnection.setDefaultSSLSocketFactory(DEFAULT_SSL_SOCKET_FACTORY);
                HttpsURLConnection.setDefaultHostnameVerifier(DEFAULT_HOSTNAME_VERIFIER);
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot apply TLS settings", e);
        }
    }

    public static boolean insecureTls() {
        return insecureTls;
    }

    /** POST a JSON body. Returns the response; never throws on non-2xx. */
    public Response postJson(String path, String jsonBody) throws IOException {
        return postJson(path, jsonBody, null);
    }

    public Response postJson(String path, String jsonBody, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (extraHeaders != null) {
            h.putAll(extraHeaders);
        }
        h.put("Content-Type", JSON_CONTENT_TYPE);
        h.put("Accept", ACCEPT_JSON);
        byte[] body = (jsonBody == null) ? new byte[0] : jsonBody.getBytes(UTF8);
        return doRequest("POST", path, h, body, false);
    }

    public Response get(String path) throws IOException {
        return get(path, null);
    }

    public Response get(String path, Map<String, String> extraHeaders) throws IOException {
        Map<String, String> h = new LinkedHashMap<String, String>();
        if (extraHeaders != null) {
            h.putAll(extraHeaders);
        }
        h.put("Accept", ACCEPT_JSON);
        return doRequest("GET", path, h, null, true);
    }

    public Response request(String method, String path,
                            Map<String, String> headers, byte[] body) throws IOException {
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be empty");
        }
        return doRequest(method.toUpperCase(), path, headers, body, true);
    }

    /**
     * Streaming download to a file with Range resume.
     * resume==true &amp;&amp; dest exists &amp;&amp; dest.length()&gt;0 -&gt; send
     * "Range: bytes=&lt;len&gt;-", append to dest. If the server answers 200 (no range
     * support) the file is truncated and rewritten from 0. 416 is retried once from
     * offset 0. Returns the final Response (status/headers; body is null, bytes are in
     * the file).
     */
    public Response downloadTo(String path, File dest, boolean resume) throws IOException {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        long existing = 0;
        if (resume && dest.isFile()) {
            existing = dest.length();
        }
        if (existing > 0) {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Range", "bytes=" + existing + "-");
            return doDownload(path, dest, h, true, true);
        }
        return doDownload(path, dest, null, false, true);
    }

    /** multipart/form-data POST with field {@code path}=&lt;relPath&gt; (omitted when null) and field {@code file}=&lt;file&gt;. */
    public Response uploadFile(String relPath, File file) throws IOException {
        return uploadFile(relPath, file, null);
    }

    /**
     * Same as {@link #uploadFile(String, File)} but lets the caller choose the multipart
     * {@code filename}. Pass {@code null} to fall back to {@code file.getName()}.
     *
     * <p>Why this exists: the picker path copies the picked document into a cache file
     * ({@code upload-<millis>}), so {@code file.getName()} is a meaningless temp name. The
     * server derives the stored name from the separate {@code path} field, but any server
     * (or log, or downstream tool) that keys off the multipart {@code filename} would
     * record the temp name. Callers that know the real name must pass it here.
     */
    public Response uploadFile(String relPath, File file, String filenameOverride) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (!file.isFile()) {
            throw new IOException("not a readable file: " + file.getAbsolutePath());
        }
        String boundary = randomBoundary();
        String filename = (filenameOverride == null || filenameOverride.isEmpty())
                ? file.getName()
                : filenameOverride;
        byte[] pathPart = null;
        byte[] relPathBytes = null;
        if (relPath != null && !relPath.isEmpty()) {
            pathPart = partHead(boundary, "path", null, null);
            relPathBytes = relPath.getBytes(UTF8);
        }
        byte[] filePart = partHead(boundary, "file", filename, "application/octet-stream");
        byte[] trailer = ("\r\n--" + boundary + "--\r\n").getBytes(UTF8);
        long contentLength = trailer.length + filePart.length + file.length();
        if (pathPart != null) {
            contentLength += pathPart.length + relPathBytes.length + CRLF.length;
        }

        HttpURLConnection conn = null;
        try {
            conn = open(fullUrl("/fs/upload"));
            applyCommon(conn);
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(contentLength);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", ACCEPT_JSON);
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            OutputStream out = conn.getOutputStream();
            try {
                if (pathPart != null) {
                    out.write(pathPart);
                    out.write(relPathBytes);
                    out.write(CRLF);
                }
                out.write(filePart);
                InputStream in = new FileInputStream(file);
                try {
                    byte[] buf = new byte[STREAM_CHUNK];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                out.write(trailer);
                out.flush();
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            InputStream in2 = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] bytes = readAll(in2);
            String text = (bytes == null) ? null : new String(bytes, UTF8);
            return new Response(status, text, bytes, conn.getHeaderFields());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * POST a byte range of {@code file} as the raw request body
     * ({@code Content-Type: application/octet-stream}) with a fixed {@code Content-Length},
     * streamed from disk. This is the transport for chunked upload (mode B, one chunk per
     * request) — see {@link GatewayClient#uploadChunked}.
     *
     * <p>Why a range and not a {@code byte[]}: a chunk is 4 MiB by default and the whole
     * point of mode B is to upload files far larger than the heap. Nothing here ever holds
     * more than one {@link #STREAM_CHUNK} buffer.</p>
     *
     * <p>{@code offset + length} must lie inside the file; a violation is an
     * {@link IOException} raised before any request is sent, not a truncated upload.</p>
     */
    public Response postFileRange(String path, File file, long offset, long length,
                                  Map<String, String> extraHeaders) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (!file.isFile()) {
            throw new IOException("not a readable file: " + file.getAbsolutePath());
        }
        long fileLength = file.length();
        if (offset < 0 || length < 0 || offset > fileLength || length > fileLength - offset) {
            throw new IOException("range " + offset + "+" + length + " is outside "
                    + file.getAbsolutePath() + " (" + fileLength + " bytes)");
        }
        HttpURLConnection conn = null;
        try {
            conn = open(fullUrl(path));
            applyCommon(conn);
            conn.setRequestMethod("POST");
            conn.setInstanceFollowRedirects(false);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Accept", ACCEPT_JSON);
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            FileInputStream in = new FileInputStream(file);
            try {
                long skipped = 0;
                while (skipped < offset) {
                    long s = in.skip(offset - skipped);
                    if (s <= 0) {
                        throw new IOException("cannot seek to offset " + offset + " in "
                                + file.getAbsolutePath());
                    }
                    skipped += s;
                }
                OutputStream out = conn.getOutputStream();
                try {
                    byte[] buf = new byte[STREAM_CHUNK];
                    long left = length;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min((long) buf.length, left));
                        if (n < 0) {
                            throw new IOException("unexpected end of " + file.getAbsolutePath()
                                    + " while sending " + length + " bytes from offset " + offset);
                        }
                        out.write(buf, 0, n);
                        left -= n;
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            int status = conn.getResponseCode();
            InputStream in2 = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] bytes = readAll(in2);
            String text = (bytes == null) ? null : new String(bytes, UTF8);
            return new Response(status, text, bytes, conn.getHeaderFields());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Streaming SHA-256 of a file, lowercase hex. Reads in {@link #STREAM_CHUNK} blocks, so
     * the file is never held in memory — the gateway verifies the whole-file hash after
     * merging the chunks (docs/GATEWAY-API.md §2.3, {@code X-File-SHA256}).
     */
    public static String sha256Hex(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable: " + e);
        }
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[STREAM_CHUNK];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(HEX[(b >> 4) & 0x0F]);
            sb.append(HEX[b & 0x0F]);
        }
        return sb.toString();
    }

    /** application/x-www-form-urlencoded style percent-encoding; space becomes %20 (not '+'). */
    public static String urlEncode(String s) {
        if (s == null) {
            return null;
        }
        byte[] b = s.getBytes(UTF8);
        StringBuilder sb = new StringBuilder(b.length);
        for (byte x : b) {
            int c = x & 0xFF;
            if (isUnreserved(c)) {
                sb.append((char) c);
            } else if (c == ' ') {
                sb.append("%20");
            } else {
                sb.append('%');
                sb.append(HEX[c >> 4]);
                sb.append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ internals

    private Response doRequest(String method, String path, Map<String, String> headers,
                               byte[] body, boolean followRedirects) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(fullUrl(path));
            applyCommon(conn);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(followRedirects);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                    out.flush();
                } finally {
                    out.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            byte[] bytes = readAll(in);
            String text = (bytes == null) ? null : new String(bytes, UTF8);
            return new Response(status, text, bytes, conn.getHeaderFields());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** One-shot GET-to-file. {@code append} is honored only when the server answers 206. */
    private Response doDownload(String path, File dest, Map<String, String> extraHeaders,
                                boolean append, boolean retry416) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(fullUrl(path));
            applyCommon(conn);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            int status = conn.getResponseCode();
            if (status == 416 && retry416) {
                conn.disconnect();
                conn = null;
                return doDownload(path, dest, null, false, false);
            }
            if (status >= 200 && status < 300) {
                File parent = dest.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                boolean keepAppend = append && status == 206;
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(dest, keepAppend);
                try {
                    byte[] buf = new byte[STREAM_CHUNK];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    try {
                        fos.close();
                    } finally {
                        in.close();
                    }
                }
            }
            return new Response(status, null, null, conn.getHeaderFields());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        return (HttpURLConnection) new URL(url).openConnection();
    }

    private void applyCommon(HttpURLConnection conn) {
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setUseCaches(false);
        if (insecureTls && conn instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) conn;
            h.setSSLSocketFactory(insecureSslFactory());
            h.setHostnameVerifier(PERMISSIVE_HOSTNAME_VERIFIER);
        }
    }

    private String fullUrl(String path) {
        if (path == null || path.isEmpty()) {
            return baseUrl;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (path.startsWith("/")) {
            return baseUrl + path;
        }
        return baseUrl + "/" + path;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static SSLSocketFactory insecureSslFactory() {
        SSLSocketFactory f = insecureSslFactory;
        if (f == null) {
            synchronized (Http.class) {
                f = insecureSslFactory;
                if (f == null) {
                    try {
                        SSLContext ctx = SSLContext.getInstance("TLS");
                        ctx.init(null, new TrustManager[]{PERMISSIVE_TRUST_MANAGER}, new SecureRandom());
                        f = ctx.getSocketFactory();
                        insecureSslFactory = f;
                    } catch (Exception e) {
                        throw new IllegalStateException("cannot create insecure TLS socket factory", e);
                    }
                }
            }
        }
        return f;
    }

    private static String randomBoundary() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder("----------------dsh");
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0x0F]);
            sb.append(HEX[x & 0x0F]);
        }
        return sb.toString();
    }

    private static byte[] partHead(String boundary, String name, String filename, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append('"');
        if (filename != null) {
            sb.append("; filename=\"").append(filename).append('"');
        }
        sb.append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(UTF8);
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }

    /** Result of a request: status, decoded body, raw bytes and case-insensitive header lookup. */
    public static final class Response {
        public final int status;
        public final String body;                       // decoded text, null for downloadTo
        public final byte[] bytes;                      // raw body, may be null
        public final Map<String, List<String>> headers;

        Response(int status, String body, byte[] bytes, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.bytes = bytes;
            this.headers = (headers == null) ? new LinkedHashMap<String, List<String>>() : headers;
        }

        /** Case-insensitive header lookup; first value or null. */
        public String header(String name) {
            if (name == null) {
                return null;
            }
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        /** status >= 200 && status < 300. */
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Json.parse(body); null if body==null or unparseable. */
        public Object json() {
            if (body == null) {
                return null;
            }
            try {
                return Json.parse(body);
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}