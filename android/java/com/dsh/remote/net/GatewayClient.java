package com.dsh.remote.net;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for the dsh-remote-gateway HTTP contract (docs/GATEWAY-API.md, frozen v1):
 * /gateway/* authentication routes and /fs/* workspace file routes.
 */
public final class GatewayClient {

    /**
     * Chunk size for the chunked upload (mode B). 4 MiB is a deliberate middle ground:
     *
     * <ul>
     *   <li>it is far below the gateway's per-request {@code maxUploadBytes} (2 GiB) and far
     *       below the reverse proxy's {@code replayBodyLimitBytes} (64 MiB) — a chunk that
     *       hits a dsh restart mid-flight can therefore be buffered and replayed by the
     *       gateway instead of coming back as {@code 502 upstream_reauth_needed};</li>
     *   <li>it keeps the number of round trips low (a 1 GiB file is 256 requests, not 1024);</li>
     *   <li>memory stays bounded: a chunk is streamed from disk, never read into a byte[].</li>
     * </ul>
     */
    public static final int CHUNK_SIZE_BYTES = 4 * 1024 * 1024;

    private final Http http;

    public GatewayClient(String baseUrl) {
        if (baseUrl == null) throw new NullPointerException("baseUrl == null");
        this.http = new Http(baseUrl);
    }

    public Http http() {
        return http;
    }

    public static final class LoginResult {
        public final String token;
        public final String username;
        public final long expiresAt;

        public LoginResult(String token, String username, long expiresAt) {
            this.token = token;
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }

    /** POST /gateway/login. On success the received Bearer token is stored on the shared Http. */
    public LoginResult login(String username, String password) throws IOException {
        Map<String, Object> body = Json.obj();
        body.put("username", username == null ? "" : username);
        body.put("password", password == null ? "" : password);
        Http.Response resp = http.postJson("/gateway/login", Json.stringify(body));
        if (!resp.ok()) throw gatewayError(resp);
        Object j = resp.json();
        String token = Json.str(j, "token");
        String uname = Json.str(j, "username");
        long expiresAt = Json.num(j, 0L, "expiresAt");
        http.setBearer(token);
        return new LoginResult(token, uname, expiresAt);
    }

    /** POST /gateway/logout. */
    public void logout() throws IOException {
        Http.Response resp = http.postJson("/gateway/logout", "{}");
        if (!resp.ok()) throw gatewayError(resp);
    }

    /** GET /gateway/health (no auth). Returns the parsed body. */
    public Object health() throws IOException {
        Http.Response resp = http.get("/gateway/health");
        if (!resp.ok()) throw gatewayError(resp);
        return resp.json();
    }

    /** GET /gateway/session -> true on 200, false on 401; any other status throws. */
    public boolean checkSession() throws IOException {
        Http.Response resp = http.get("/gateway/session");
        if (resp.status == 200) return true;
        if (resp.status == 401) return false;
        throw gatewayError(resp);
    }

    /** GET /fs/list?path=<rel>. Returns the {@code entries} List (never null; empty when absent). */
    public List<Object> fsList(String relPath) throws IOException {
        Object body = fsListRaw(relPath);
        return Json.list(body, "entries");
    }

    /** Full parsed body of GET /fs/list. */
    public Object fsListRaw(String relPath) throws IOException {
        String path = relPath == null ? "" : relPath;
        Http.Response resp = http.get("/fs/list?path=" + Http.urlEncode(path));
        if (!resp.ok()) throw gatewayError(resp);
        return resp.json();
    }

    /** GET /fs/download -> file, with Range resume. Returns bytes on disk after the call. */
    public long download(String relPath, File dest, boolean resume) throws IOException {
        String path = relPath == null ? "" : relPath;
        Http.Response resp = http.downloadTo("/fs/download?path=" + Http.urlEncode(path), dest, resume);
        // 必须检查状态码：downloadTo 只在 2xx 时才会创建目标文件，所以 403/404 会
        // **一个字节都不写**。早期实现不看状态就返回 dest.length()，于是「路径越界被拒」
        // 会被 UI 显示成 “Saved: <path>”，用户以为下载成功了。
        if (!resp.ok()) throw downloadError(resp.status, path);
        return dest.length();
    }

    /**
     * 下载失败时的错误。downloadTo 不保留响应体（字节都进文件了），所以这里拿不到
     * 服务端的 {@code error} 字段，只能按状态码给出可读原因。
     */
    private GatewayException downloadError(int status, String path) {
        String error;
        String detail;
        if (status == 403) {
            error = "path_denied";
            detail = "下载被拒：路径越界或不可读（" + path + "）";
        } else if (status == 404) {
            error = "not_found";
            detail = "下载失败：文件不存在（" + path + "）";
        } else if (status == 401) {
            error = "unauthorized";
            detail = "下载失败：会话已失效，请重新登录";
        } else if (status == 416) {
            error = "range_not_satisfiable";
            detail = "下载失败：续传范围不合法（" + path + "）";
        } else {
            error = "download_failed";
            detail = "下载失败：HTTP " + status + "（" + path + "）";
        }
        return new GatewayException(status, error, detail, null);
    }

    /** POST /fs/upload (multipart, mode A). Returns the parsed body. */
    public Object upload(String relPath, File file) throws IOException {
        return upload(relPath, file, null);
    }

    /**
     * Same as {@link #upload(String, File)} but with an explicit multipart {@code filename}.
     * Pass the real name when {@code file} is a temp copy — see
     * {@link Http#uploadFile(String, File, String)}.
     */
    public Object upload(String relPath, File file, String filename) throws IOException {
        Http.Response resp = http.uploadFile(relPath, file, filename);
        if (!resp.ok()) throw gatewayError(resp);
        return resp.json();
    }

    // ------------------------------------------------------------ mode B (chunked)

    /** One progress callback per successfully accepted chunk, on the calling thread. */
    public interface UploadProgress {
        /**
         * @param chunkIndex   1-based number of the chunk that was just accepted
         * @param totalChunks  total number of chunks this upload consists of
         * @param bytesSent    bytes of the file accepted so far
         * @param totalBytes   the file's size
         */
        void chunkSent(int chunkIndex, int totalChunks, long bytesSent, long totalBytes);
    }

    /** The contract's mode-B response: {@code completed} plus, once merged, path/size/sha256. */
    public static final class ChunkedUploadResult {
        public final boolean completed;
        public final int receivedChunks;
        public final int totalChunks;
        public final String path;      // null unless completed
        public final long size;        // -1 unless completed
        public final String sha256;    // null unless completed

        ChunkedUploadResult(boolean completed, int receivedChunks, int totalChunks,
                            String path, long size, String sha256) {
            this.completed = completed;
            this.receivedChunks = receivedChunks;
            this.totalChunks = totalChunks;
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    /**
     * POST /fs/upload in <b>mode B</b> (chunked): one {@code application/octet-stream} request
     * per 4 MiB slice of {@code file}, all carrying the same {@code X-Upload-Id} and a
     * 0-based, strictly increasing {@code X-Chunk-Index} (docs/GATEWAY-API.md §2.3).
     *
     * <p>The whole-file SHA-256 is computed by streaming the file and is sent on
     * <b>every</b> chunk. The contract says "supply on any one chunk", but the gateway's
     * implementation ({@code dsh-remote-gateway/src/files/routes.ts}, {@code uploadChunk}) only
     * reads {@code x-file-sha256} off the request that completes the upload, so sending it on
     * the first chunk alone would silently skip verification. It is sent on the first chunk as
     * required and on the rest for the same reason.</p>
     *
     * <p>Aborts on the first chunk the server rejects: the exception carries the chunk number
     * and the gateway's own error, and no further chunk is sent.</p>
     *
     * @param relPath  target path relative to the workspace root; may be an existing directory,
     *                 in which case the gateway uses {@code filename} for the file name
     * @param filename optional {@code X-File-Name}; null/empty omits the header
     * @param progress optional per-chunk callback (may be null)
     */
    public ChunkedUploadResult uploadChunked(String relPath, File file, String filename,
                                             UploadProgress progress) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (!file.isFile()) {
            throw new IOException("not a readable file: " + file.getAbsolutePath());
        }
        final String target = (relPath == null) ? "" : relPath;
        final long fileLength = file.length();
        // An empty file is still one (empty) chunk: the contract requires total >= 1 and
        // index < total, so total == 0 would be rejected as bad_request.
        long chunks = (fileLength + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES;
        if (chunks < 1) {
            chunks = 1;
        }
        final int total = (int) chunks;
        final String uploadId = Uuid.v4();       // [0-9a-f-] — matches the gateway's id pattern
        final String encodedPath = Http.urlEncode(target);
        final String sha256 = Http.sha256Hex(file);   // streamed, not buffered

        ChunkedUploadResult last = null;
        for (int index = 0; index < total; index++) {
            long offset = (long) index * CHUNK_SIZE_BYTES;
            long length = Math.min((long) CHUNK_SIZE_BYTES, fileLength - offset);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("X-Upload-Id", uploadId);
            headers.put("X-Chunk-Index", String.valueOf(index));
            headers.put("X-Total-Chunks", String.valueOf(total));
            headers.put("X-File-Path", encodedPath);
            if (filename != null && !filename.isEmpty()) {
                headers.put("X-File-Name", filename);
            }
            headers.put("X-File-SHA256", sha256);

            Http.Response resp = http.postFileRange("/fs/upload", file, offset, length, headers);
            if (!resp.ok()) {
                throw chunkError(index, total, resp);
            }
            Object body = resp.json();
            if (!(body instanceof Map)) {
                throw new IOException("chunked upload: unparseable response for chunk "
                        + (index + 1) + "/" + total + " (HTTP " + resp.status + ")");
            }
            if (!Json.bool(body, true, "ok")) {
                throw chunkError(index, total, resp);
            }
            last = new ChunkedUploadResult(
                    Json.bool(body, false, "completed"),
                    (int) Json.num(body, index + 1, "receivedChunks"),
                    (int) Json.num(body, total, "totalChunks"),
                    Json.str(body, "path"),
                    Json.num(body, -1L, "size"),
                    Json.str(body, "sha256"));
            if (progress != null) {
                progress.chunkSent(index + 1, total, offset + length, fileLength);
            }
            if (last.completed) {
                return last;
            }
        }
        // Every chunk was accepted but the server never said the merge happened. Reporting
        // success here would be a lie: nothing is guaranteed to be at the target path.
        throw new IOException("chunked upload did not complete: the server accepted all "
                + total + " chunk(s) but never answered completed=true (last: receivedChunks="
                + (last == null ? "?" : String.valueOf(last.receivedChunks)) + ")");
    }

    /** {@link #uploadChunked} without a progress callback. */
    public ChunkedUploadResult uploadChunked(String relPath, File file, String filename)
            throws IOException {
        return uploadChunked(relPath, file, filename, null);
    }

    /** Wraps the gateway's error with the chunk that caused it, so the UI can say which one. */
    private GatewayException chunkError(int index, int total, Http.Response resp) {
        GatewayException base = gatewayError(resp);
        String detail = "分块上传失败：第 " + (index + 1) + "/" + total + " 块被拒（"
                + base.error + "：" + base.detail + "）";
        return new GatewayException(resp.status, base.error, detail, base.body);
    }

    private GatewayException gatewayError(Http.Response resp) {
        Object parsed = null;
        String error = null;
        String detail = null;
        try {
            parsed = resp.json();
        } catch (RuntimeException ignore) {
            // unparseable body: fall back to defaults below
        }
        if (parsed instanceof Map) {
            error = Json.str(parsed, "error");
            detail = Json.str(parsed, "message");
        }
        if (error == null) error = "http/" + resp.status;
        if (detail == null) {
            detail = (resp.body == null || resp.body.length() == 0)
                    ? ("HTTP " + resp.status) : resp.body;
        }
        return new GatewayException(resp.status, error, detail, parsed);
    }
}