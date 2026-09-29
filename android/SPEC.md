# android/ — FROZEN INTERFACE SPEC (internal, for implementers)

Project root for this deliverable: `/root/project/remote-use/android/`
**Never write outside `/root/project/remote-use/`.**

## 0. Hard environment constraints (do not fight them)

* aarch64 **Kylin Linux**. There is **NO Gradle, NO Kotlin, NO Android Studio, NO AndroidX,
  NO Compose, NO OkHttp, NO Gson/org.json on the JDK**.
* The app is **plain Java, `-source 8 -target 8`, Android framework APIs only**, built by a
  hand-rolled `aapt2 + d8 + apksigner` pipeline.
* `net/` is **PURE Java**: it must compile and run on a desktop JDK 17 with **zero**
  `android.*` / `org.json` / AndroidX imports. A JVM harness runs it against a live gateway.
* Java 8 language level for everything under `java/`. Do **not** use `var`, records,
  switch-expressions, `List.of`, `Map.of`, `String.isBlank`, `InputStream.readAllBytes`,
  `Files.readString`, text blocks, or `java.net.http.HttpClient`.
  (`java.util.Base64` IS available on Android API 26+ and on JDK 8+, so it is allowed.)

## 1. File layout (exact)

```
/root/project/remote-use/android/
├── build_apk.sh                     # re-runnable from scratch, prints APK path
├── AndroidManifest.xml
├── res/values/strings.xml
├── res/drawable/icon.png            # generated 96x96 PNG
├── java/com/dsh/remote/
│   ├── net/                         # PURE JAVA — no android imports
│   │   ├── Json.java
│   │   ├── Http.java
│   │   ├── Uuid.java
│   │   ├── GatewayException.java
│   │   ├── DshException.java
│   │   ├── WebSocketClient.java
│   │   ├── GatewayClient.java
│   │   └── DshRpcClient.java
│   └── ui/                          # Android only
│       ├── App.java
│       ├── LoginActivity.java
│       ├── SessionListActivity.java
│       ├── ChatActivity.java
│       └── FilesActivity.java
├── verify/
│   ├── VerifyMain.java              # package com.dsh.remote.verify
│   └── run_verify.sh
├── tools/verify_apk.py
└── README.md
```

Java package root is `com.dsh.remote`. Android `applicationId`/manifest package is
`com.dsh.remote`.

## 2. `net/` — exact public API (implementers MUST match these signatures)

### `Json.java` — value model + parser + serializer

Value model uses plain JDK types:

| JSON | Java |
|---|---|
| object | `java.util.LinkedHashMap<String,Object>` |
| array | `java.util.ArrayList<Object>` |
| string | `String` |
| number | `Long` if integral (no `.`/`e`/`E` in the literal), else `Double` |
| true/false | `Boolean` |
| null | `null` |

```java
package com.dsh.remote.net;

public final class Json {
    public static final class JsonException extends RuntimeException {
        public JsonException(String m); public JsonException(String m, Throwable c);
    }
    /** Parse a complete JSON document. Trailing whitespace allowed, trailing garbage is an error. */
    public static Object parse(String text) throws JsonException;
    /** Compact serialization. Map keys keep insertion order. */
    public static String stringify(Object v);
    /** Indented serialization, 2 spaces. */
    public static String pretty(Object v);
    /** New empty ordered object / array. */
    public static java.util.Map<String,Object> obj();
    public static java.util.List<Object> arr();
    /** Path walk; returns null when missing. Path elements are object keys, or array indices when the node is a List and the element parses as an int. */
    public static Object get(Object node, String... path);
    public static String  str(Object node, String... path);          // null when absent / not a String
    public static String  str(Object node, String def, String... path); // hmm: ambiguous with varargs; DO NOT ADD
    public static long    num(Object node, long def, String... path);
    public static boolean bool(Object node, boolean def, String... path);
    public static java.util.Map<String,Object> map(Object node, String... path);   // empty map when absent
    public static java.util.List<Object> list(Object node, String... path);        // empty list when absent
}
```

> NOTE on `str`: implement **only**
> `public static String str(Object node, String... path)` → returns `null` if absent or not a
> `String`. Do not add a defaulted overload (it would be ambiguous with varargs).
> Callers use `Json.str(...)` and fall back themselves.

Parser requirements: correct handling of `\uXXXX` escapes, `\/`, surrogate pairs, nested
structures, big integers (fall back to `Double` when the literal does not fit a `long`),
reject unterminated strings/objects and control characters inside strings.
Serializer requirements: escape `"`, `\`, `\b\f\n\r\t`, and all chars `< 0x20` as `\u00XX`;
do **not** escape non-ASCII (emit UTF-8 as-is); `Double` output must be finite and not print
`1.0E10`-style for integral values (print `1.0E10` is acceptable only for genuinely huge
values — prefer `String.valueOf(double)` semantics but strip a trailing `.0` only when the
value is integral and `|v| < 1e15`).

### `Http.java` — HTTP client on `java.net.HttpURLConnection`

```java
package com.dsh.remote.net;

public final class Http {
    public Http(String baseUrl);                  // e.g. "https://gw.example.com", "http://127.0.0.1:3081"; trailing '/' stripped
    public String baseUrl();
    public void setBearer(String token);          // null/empty clears; adds "Authorization: Bearer <t>"
    public String bearer();
    public void setConnectTimeoutMs(int ms);      // default 15000
    public void setReadTimeoutMs(int ms);         // default 60000
    /** Opt-in trust-all TLS (self-signed test gateways). Default false. Applies to HttpURLConnection and is read by WebSocketClient too. */
    public static void setInsecureTls(boolean on);
    public static boolean insecureTls();

    /** POST a JSON body. Returns the response; never throws on non-2xx. */
    public Response postJson(String path, String jsonBody) throws java.io.IOException;
    public Response postJson(String path, String jsonBody, java.util.Map<String,String> extraHeaders) throws java.io.IOException;
    public Response get(String path) throws java.io.IOException;
    public Response get(String path, java.util.Map<String,String> extraHeaders) throws java.io.IOException;
    public Response request(String method, String path,
                            java.util.Map<String,String> headers, byte[] body) throws java.io.IOException;

    /**
     * Streaming download to a file with Range resume.
     * resume==true && dest exists && dest.length()>0  ->  send "Range: bytes=<len>-", append to dest.
     * If the server answers 200 (no range support) the file is truncated and rewritten from 0.
     * 416 -> retried once from offset 0.
     * Returns the final Response (status/headers; body is null, bytes are in the file).
     */
    public Response downloadTo(String path, java.io.File dest, boolean resume) throws java.io.IOException;

    /** multipart/form-data POST with field `path`=<relPath> (omitted when null) and field `file`=<file>. */
    public Response uploadFile(String relPath, java.io.File file) throws java.io.IOException;

    /**
     * Same, but with an explicit multipart `filename`. Pass the real name when `file` is a
     * temp copy (the document-picker path copies to `cacheDir/upload-<millis>`).
     * null/empty -> falls back to `file.getName()`.
     */
    public Response uploadFile(String relPath, java.io.File file, String filenameOverride) throws java.io.IOException;

    /**
     * POST a byte RANGE of a file as the raw body (`application/octet-stream`) — the
     * chunked-upload (mode B) primitive. Streamed in 64 KiB blocks; range bounds are
     * validated before any request is sent.
     */
    public Response postFileRange(String path, java.io.File file, long offset, long length,
                                  java.util.Map<String,String> extraHeaders) throws java.io.IOException;

    /** Streaming SHA-256 of a file, lowercase hex. */
    public static String sha256Hex(java.io.File file) throws java.io.IOException;

    public static String urlEncode(String s);      // application/x-www-form-urlencoded style (space -> %20)

    public static final class Response {
        public final int status;
        public final String body;                       // decoded text, null for downloadTo
        public final byte[] bytes;                      // raw body, may be null
        public final java.util.Map<String,java.util.List<String>> headers;
        public String header(String name);              // case-insensitive, first value or null
        public boolean ok();                            // status >= 200 && < 300
        public Object json();                           // Json.parse(body), null if body==null/unparseable
    }
}
```

Rules: always set `Content-Type: application/json; charset=utf-8` on `postJson`; send
`Accept: application/json`; read error streams on non-2xx (`getErrorStream()`) so the contract's
JSON error bodies are available. Use `setInstanceFollowRedirects(false)` for `postJson`
(the gateway's `POST /gateway/login` never redirects; following a 302 to `/login` would
corrupt the result). For `get`, follow redirects normally.

### `Uuid.java`

```java
package com.dsh.remote.net;
public final class Uuid {
    /** RFC 4122 version 4, lowercase, e.g. "3f0c...-....". SecureRandom-backed. */
    public static String v4();
}
```

### Exceptions

```java
package com.dsh.remote.net;

/** Gateway-level failure: HTTP status + the contract's {"ok":false,"error":..,"message":..} body. */
public class GatewayException extends java.io.IOException {
    public final int status;      // HTTP status (0 when unknown)
    public final String error;    // e.g. "invalid_credentials", "rate_limited", "not_configured"
    public final String detail;   // human-readable message, never null
    public final Object body;     // parsed JSON body or null
    public GatewayException(int status, String error, String detail, Object body);
}

/** dsh RPC business failure: HTTP 200 with result.ok==false. */
public class DshException extends java.io.IOException {
    public final String code;     // e.g. "gateway/bad-request"
    public final String detail;
    public final Object details;
    public DshException(String code, String detail, Object details);
}
```

### `WebSocketClient.java` — RFC 6455 over `Socket`/`SSLSocket` (pure Java)

```java
package com.dsh.remote.net;

public final class WebSocketClient {
    public interface Listener {
        void onOpen();
        void onText(String message);
        void onBinary(byte[] data);           // optional; may be left as a no-op body
        void onClose(int code, String reason);
        void onError(Throwable error);
    }

    public WebSocketClient(java.net.URI uri, java.util.Map<String,String> headers, Listener listener);

    /** Blocking: TCP connect (ws/wss chosen from the URI scheme), HTTP upgrade handshake,
     *  verify 101 + Sec-WebSocket-Accept, then start the reader thread. Throws IOException
     *  carrying the server's status line/body on a non-101 response. */
    public void connect() throws java.io.IOException;

    /** Thread-safe. Sends one masked text frame (FIN=1). */
    public void sendText(String message) throws java.io.IOException;
    /** Thread-safe. Sends a masked Ping control frame. */
    public void sendPing(byte[] payload) throws java.io.IOException;
    /** Thread-safe, idempotent. Sends a masked Close frame (code 1000 default) then closes the socket. */
    public void close(int code, String reason);

    public boolean isOpen();
    public String closeReason();          // last close code/reason, for diagnostics

    /** Socket SO_TIMEOUT for the reader, default 0 (blocking). Must stay 0 unless you also
     *  implement read-loop liveness handling — an SO_TIMEOUT throwing SocketTimeoutException
     *  must NOT be treated as a fatal error. */
    public void setReadTimeoutMs(int ms);
    /** Client-initiated keepalive: send a Ping every N seconds of write-idleness.
     *  Default 20s; 0 disables. A missing Pong for 2 intervals closes the socket with 1001. */
    public void setClientPingSeconds(int seconds);
}
```

Wire requirements (RFC 6455):

* Handshake request (CRLF-terminated), on top of any caller headers
  (`Authorization: Bearer …` is passed in by the caller — do not invent it):
  `GET <path?query> HTTP/1.1`, `Host: <host[:port]>`, `Upgrade: websocket`,
  `Connection: Upgrade`, `Sec-WebSocket-Key: <16 random bytes, base64>`,
  `Sec-WebSocket-Version: 13`.
* Expect `HTTP/1.1 101` and
  `Sec-WebSocket-Accept == base64(sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`.
  `sha1` via `java.security.MessageDigest.getInstance("SHA-1")`.
* **Every client frame MUST be masked** with a fresh 4-byte mask from `SecureRandom`.
* Read side: honour FIN + continuation (opcode 0x0), handle payload lengths 7-bit / 7+16 /
  7+64, reject RSV != 0 and masked server frames.
* opcode 0x1 text → decode UTF-8 → `onText`. Server text frames may be fragmented.
* opcode 0x9 Ping → **immediately reply Pong with the same payload** (this is mandatory:
  dsh pings every 2000 ms and terminates the socket without a Pong). opcode 0xA Pong → clear
  the outstanding-ping state. opcode 0x8 Close → echo Close once, close socket, `onClose`.
* On EOF / IOException: `onClose` (once) — never both `onClose` and `onError` for the same
  termination, and never more than once total (`AtomicBoolean` guard).
* Threading: one reader thread started by `connect()`; writes synchronised on a lock;
  the keepalive ping runs on a daemon `ScheduledExecutorService` (or a daemon Thread with
  `Object.wait`). All threads must be daemon so a JVM harness can exit.
* UTF-8 decode/encode explicitly (`new String(b, StandardCharsets.UTF_8)`), never platform default.

### `GatewayClient.java`

```java
package com.dsh.remote.net;

public final class GatewayClient {
    public GatewayClient(String baseUrl);
    public Http http();

    public static final class LoginResult {
        public final String token, username; public final long expiresAt;
    }

    /** POST /gateway/login. Throws GatewayException on 401/429/503/400. */
    public LoginResult login(String username, String password) throws java.io.IOException;
    /** POST /gateway/logout. */
    public void logout() throws java.io.IOException;
    /** GET /gateway/health (no auth). */
    public Object health() throws java.io.IOException;
    /** GET /gateway/session -> true on 200, false on 401; other statuses throw. */
    public boolean checkSession() throws java.io.IOException;

    /** GET /fs/list?path=<rel>. Returns the `entries` List. */
    public java.util.List<Object> fsList(String relPath) throws java.io.IOException;
    /** Full parsed body of GET /fs/list. */
    public Object fsListRaw(String relPath) throws java.io.IOException;
    /** GET /fs/download -> file, with Range resume. Returns bytes on disk after the call.
     *  Throws GatewayException on a non-2xx status (a 403 leaves no file behind, so
     *  returning dest.length() would report success for a download that never happened). */
    public long download(String relPath, java.io.File dest, boolean resume) throws java.io.IOException;
    /** POST /fs/upload (multipart, mode A). Returns the parsed body. */
    public Object upload(String relPath, java.io.File file) throws java.io.IOException;
    /** Mode A with an explicit multipart `filename` (see Http.uploadFile). */
    public Object upload(String relPath, java.io.File file, String filename) throws java.io.IOException;

    /** Chunk size for mode B. 4 MiB — deliberately below the gateway's maxUploadBytes (2 GiB)
     *  and the reverse proxy's replayBodyLimitBytes (64 MiB), so a chunk meeting a dsh restart
     *  can be replayed rather than 502'd. */
    public static final int CHUNK_SIZE_BYTES = 4 * 1024 * 1024;

    /** Progress callback for uploadChunked; called after each chunk is accepted. */
    public interface UploadProgress { void onChunk(int sent, int total, long bytesSent, long bytesTotal); }

    /** Result of a completed mode-B upload. */
    public static final class ChunkedUploadResult {
        public final boolean completed; public final int receivedChunks; public final int totalChunks;
        public final String path; public final long size; public final String sha256;
    }

    /**
     * POST /fs/upload in chunked mode B: one request per CHUNK_SIZE_BYTES, with
     * X-Upload-Id / X-Chunk-Index / X-Total-Chunks / X-File-Path / X-File-Name / X-File-SHA256.
     * The whole-file SHA-256 is sent on EVERY chunk (the gateway only reads it off the
     * completing request, so first-chunk-only would silently skip verification).
     * Aborts on the first rejected chunk; never reports a partial upload as success.
     */
    public ChunkedUploadResult uploadChunked(String relPath, java.io.File file, String filename,
                                             UploadProgress progress) throws java.io.IOException;
}
```

`login()` **must** call `http().setBearer(token)` on success before returning.
On non-2xx it parses `{"ok":false,"error":…,"message":…}` (missing fields tolerated) and
throws `GatewayException`.

### `DshRpcClient.java`

```java
package com.dsh.remote.net;

public final class DshRpcClient {
    /** Shares the Http instance (base URL + Bearer token). */
    public DshRpcClient(Http http);

    /**
     * POST /api/<ns>/<method> with
     * {"type":"client-request","rpcId":"<uuid4>","method":"<ns>/<method>","payload":{"args":<args>}}
     * Validates the echoed rpcId, then:
     *   result.ok==true  -> returns result.value
     *   result.ok==false -> throws DshException(error.code, error.message, error.details)
     * Non-2xx -> throws DshException("http/<status>", "<body>", null).
     * `args` may be null (sends {"args":{}}).
     */
    public Object call(String nsMethod, java.util.Map<String,Object> args) throws java.io.IOException;

    public interface StreamListener {
        void onValue(Object value);   // the mux frame `value`
        void onError(Object error);   // {"code":…,"message":…,"details":…}
        void onEnd();
    }

    /** One shared WebSocket to <base>/api/remote.mux, opened lazily. Callable from any thread. */
    public final class Mux {
        public void open(String streamId, String endpoint, java.util.Map<String,Object> args,
                         StreamListener listener) throws java.io.IOException;
        public void cancel(String streamId);
        public void close();
        public boolean isOpen();
    }
    public Mux mux();

    // ---- convenience wrappers (exact wire shapes from dsh-transport-spec.md) ----
    /** session/list, args {"_request":{}} (UNDERSCORE) -> value.items (List) */
    public java.util.List<Object> sessionList() throws java.io.IOException;
    /** session/create, args {"request":{...}}. cwd==null -> omit the key. -> sessionId */
    public String sessionCreate(String cwd) throws java.io.IOException;
    /** session/prompt, args {"request":{"requestId":uuid,"sessionId":sid,"mode":"queue","content":[{"type":"text","text":t}]}} */
    public void sessionPrompt(String sessionId, String text) throws java.io.IOException;
    /** session/cancel, args {"request":{"sessionId":sid}} */
    public void sessionCancel(String sessionId) throws java.io.IOException;
    /** $events/result, args {"clientId":cid,"eventId":eid,"outcome":outcome} */
    public void eventsResult(String clientId, String eventId, java.util.Map<String,Object> outcome) throws java.io.IOException;
    /** Convenience: open a `session/follow` stream with assistantStream:true. */
    public void followSession(String streamId, String sessionId, StreamListener listener) throws java.io.IOException;
    /** Convenience: open the `$events` stream. */
    public void openEvents(String streamId, StreamListener listener) throws java.io.IOException;
}
```

Mux framing (text frames only — **binary frames are refused by the server with close 1003**):

* `{"type":"open","streamId":"<s>","endpoint":"<ep>","payload":{"args":<args>}}`
* `{"type":"cancel","streamId":"<s>"}`
* Inbound `{"type":"item","streamId":"<s>","value":<frame>}` → `listener.onValue(frame)`
* Inbound `{"type":"error","streamId":"<s>","error":{…}}` → `listener.onError(err)` then the
  stream is removed
* Inbound `{"type":"end","streamId":"<s>"}` → `listener.onEnd()` then the stream is removed
* `open()` must tolerate the mux socket not being connected yet: connect on first `open()`.
  The WS URL is the `Http` base URL with scheme `http→ws`, `https→wss` and path
  `/api/remote.mux`, carrying `Authorization: Bearer <token>` from `Http`.
* Stream ids are caller-supplied; use distinct ids per logical stream (`"s1"`, `"ev"`).

## 3. UI contract (Android package `com.dsh.remote.ui`)

Frozen `net/` API above is the only network surface. No XML layouts except
`res/values/strings.xml` (app_name) and `res/drawable/icon.png`; **all views are built
programmatically**.

* `App` — constants + `SharedPreferences` store: keys `base_url`, `username`, `token`,
  `expires_at`; helper `DshRpcClient rpc(Context)` building `Http` with the stored bearer.
  Token stored in plain `SharedPreferences` (MODE_PRIVATE); see README for the honest note.
* `LoginActivity` (launcher) — server URL / username / password `EditText`s + Login button;
  on create, if a stored token validates via `GatewayClient.checkSession()` → jump to
  `SessionListActivity`. Background thread + `runOnUiThread`; errors in a status `TextView`.
* `SessionListActivity` — `session/list` listing (`sessionId`, `cwd`, `updatedAt`, `running`),
  a "New session" action (prompts for cwd, calls `session/create`), tap → `ChatActivity`
  (`EXTRA_SESSION_ID`), menu/button → `FilesActivity`, button → logout.
* `ChatActivity` — transcript in a `ScrollView`+`TextView` (append-only), input `EditText` +
  Send; on start opens the mux, `$events` (streamId `ev`) and `session/follow` (streamId `s1`,
  `assistantStream:true`). Renders `assistant-stream`/`chunk` frames incrementally, plus
  `event` frames of type `user/message`, `assistant/message`, `tool/call`, `tool/result`,
  `turn/end`. Approval: on `$events` `waterfall` with `event:"approval/request"`, show an
  `AlertDialog` (Allow once / Reject) and POST `$events/result` with
  `{"kind":"next"}` / `{"kind":"rejected",...}`. Cancel button → `session/cancel`.
* `FilesActivity` — `/fs/list` browser with breadcrumb/up, tap file → download into
  `getExternalFilesDir(DIRECTORY_DOWNLOADS)` (no runtime permission needed) and report the
  path; Upload button → `ACTION_OPEN_DOCUMENT` → read bytes via `ContentResolver` into a
  cache file → `GatewayClient.upload(relPath, file)`.

`AndroidManifest.xml`: `package="com.dsh.remote"`, `minSdkVersion 26`,
`targetSdkVersion 34`, `<uses-permission android:name="android.permission.INTERNET"/>`,
`android:usesCleartextTraffic="true"`, `android:label="@string/app_name"`,
`android:icon="@drawable/icon"`, `android:theme="@android:style/Theme.Material.Light.DarkActionBar"`,
`android:allowBackup="false"`, activities `android:exported` set explicitly
(LoginActivity has MAIN/LAUNCHER and `exported="true"`; the rest `exported="false"`).

## 4. Protocol facts (authoritative — from `/root/project/remote-use/docs/dsh-transport-spec.md`
and `/root/project/remote-use/docs/GATEWAY-API.md`; read both before coding)

* Unary call: `POST /api/<ns>/<method>`, `Content-Type: application/json`, body
  `{"type":"client-request","rpcId":"<uuid4>","method":"<ns>/<method>","payload":{"args":{…}}}`.
  Reply `{"type":"server-response","rpcId":"<same>","result":{"ok":true,"value":…}}`.
  **Business errors are HTTP 200 with `result.ok==false`.**
* `session/list` args use the underscore key `{"_request":{}}` → `{"items":[…]}`.
* There is **no `session/send`**; sending a message is `session/prompt`.
* Streaming: **one** WebSocket at `/api/remote.mux`, text frames only.
  `session/follow` item values are `{"type":"event",…}` | `{"type":"snapshot",…}` |
  `{"type":"assistant-stream","frame":{…}}`; live tokens are the `assistant-stream` `chunk`
  frames (`chunk.chunk` is a JSON value — usually `{"type":"text-delta","text":"…"}`; treat
  unknown shapes defensively). There is **no `assistant/chunk` event**.
* dsh sends a WS **Ping every 2000 ms**; a client that does not Pong is terminated.
* Auth: `Authorization: Bearer <token>` on `/gateway/*`, `/fs/*` and the WS handshake.
