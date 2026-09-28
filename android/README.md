# android/ — DSH Remote (plain-Java Android client)

An Android client for `dsh-remote-gateway`. It logs in to the gateway, lists/creates dsh
sessions, streams live assistant output over dsh's own WebSocket mux, answers tool-approval
prompts, and browses/uploads/downloads workspace files.

**No Gradle. No Kotlin. No AndroidX. No Compose. No OkHttp. No Gson.** Plain Java
(`-source 8 -target 8`) against Android framework APIs only, built by a hand-rolled
`aapt2 + d8 + apksigner` pipeline. See [Why](#why-it-looks-like-this) for the constraints
that forced this.

---

## 1. Layout

```
android/
├── build_apk.sh                       # the whole build, re-runnable from scratch
├── AndroidManifest.xml                # package com.dsh.remote, minSdk 26, targetSdk 34
├── res/values/strings.xml             # app_name
├── res/drawable/icon.png              # 96x96 launcher icon
├── java/com/dsh/remote/
│   ├── net/                           # PURE Java — zero android.* imports, runs on a desktop JVM
│   │   ├── Json.java                  # JSON value model + parser + serializer (no org.json)
│   │   ├── Http.java                  # HttpURLConnection client: JSON POST, Range-resume
│   │   │                              #   download to file, multipart upload
│   │   ├── WebSocketClient.java       # RFC 6455 client over Socket/SSLSocket, Ping to Pong
│   │   ├── GatewayClient.java         # /gateway/* and /fs/*
│   │   ├── DshRpcClient.java          # /api/<ns>/<method> envelope + the /api/remote.mux mux
│   │   ├── Uuid.java, GatewayException.java, DshException.java
│   └── ui/                            # Android only, programmatic views (no XML layouts)
│       ├── App.java                   # SharedPreferences store + shared helpers
│       ├── LoginActivity.java         # launcher
│       ├── SessionListActivity.java
│       ├── ChatActivity.java          # live stream + approval dialog
│       └── FilesActivity.java
├── verify/
│   ├── VerifyMain.java                # live end-to-end harness against a real gateway
│   ├── PingHoldCheck.java             # holds the WS open to exercise the Ping to Pong path
│   └── run_verify.sh                  # compiles net/ + verify/ on the JDK only, then runs
├── tools/
│   ├── verify_apk.py                  # APK installability self-check
│   └── mock_gateway.py                # TEST DOUBLE of the gateway (see §6) — not the gateway
├── dist/
│   ├── DshRemote-debug.apk            # the deliverable
│   └── build.log                      # log of a clean from-scratch build
├── verify-out/                        # harness + mock logs from the verification runs
└── SPEC.md                            # internal frozen interface spec used to build this
```

`net/` is deliberately Android-free: it compiles and runs on a desktop JDK 17, which is how
it is verified without a device.

---

## 2. Build

```bash
cd /root/project/remote-use/android
bash build_apk.sh
```

Re-runnable from scratch: `rm -rf dist && bash build_apk.sh` also works and regenerates the
debug keystore. The script prints `APK: <absolute path>` as its final labelled line and exits
non-zero on any failure. It ends by running `tools/verify_apk.py` and `aapt2 dump badging`.

**Output: `/root/project/remote-use/android/dist/DshRemote-debug.apk`** (96.5 KB)

The pipeline (7 steps): `aapt2 compile` → `aapt2 link` (both x86_64, run through
`qemu-x86_64-static -L x86root`) → `javac -source 8 -target 8 -bootclasspath android.jar` →
`d8` → ZIP assembly with `resources.arsc` and `classes.dex` **STORED** (not deflated — the
Android 11+ hard requirement from `LESSONS.md:51-53`) → `zipalign -f -p 4` → `apksigner`
sign with v1+v2+v3.

### Install

```bash
adb install -r /root/project/remote-use/android/dist/DshRemote-debug.apk
```

It is signed with a **debug keystore** (`dist/debug.keystore`, password `android`) — fine for
sideloading, not for distribution. `minSdkVersion 26` (Android 8.0) means it will not install
on Android 7 or older; that floor is needed for `java.util.Base64` and is comfortable for
`targetSdkVersion 34`.

The app is a launcher app: on first run it shows the login screen (server URL, username,
password), stores the gateway token, and then goes to the session list.

---

## 3. Verify the APK

```bash
python3 /root/project/remote-use/android/tools/verify_apk.py \
        /root/project/remote-use/android/dist/DshRemote-debug.apk
```

Checks the Android 11+ hard requirements (`resources.arsc` uncompressed **and** data-offset
4-byte aligned, `classes.dex` present and aligned, manifest present, signing artefacts) and
exits 0/1. It is adapted from `/root/project/face/android-sdk/tools/verify_apk.py` with the
face-specific `.so` checks removed — this app ships no native libraries.

---

## 4. The JVM test harness (this is how `net/` is verified)

```bash
cd /root/project/remote-use/android
bash verify/run_verify.sh --url http://127.0.0.1:3081 --user admin --pass 'your-password'
```

Positional arguments work too: `bash verify/run_verify.sh <url> <user> <pass>`.

Options: `--url --user --pass` (required), `--cwd <absPath>` (passed to `session/create`;
omitted means "use dsh's default"), `--prompt <text>`, `--wait <seconds>` (default 120),
`--insecure` (trust-all TLS, for self-signed test gateways), `--out <dir>` (default
`./verify-out`).

`run_verify.sh` compiles `net/` + `verify/` with **`javac --release 8` and no android.jar on
the classpath at all**, then runs `com.dsh.remote.verify.VerifyMain`. That is the point: if it
compiles and runs here, `net/` genuinely has no Android dependency.

Steps exercised: `GET /gateway/health` → `POST /gateway/login` → `GET /gateway/session` →
`session/list` → `session/create` → WebSocket `/api/remote.mux` opening both `$events` and
`session/follow` → `session/prompt` → **prints every streamed `assistant-stream` chunk live**
→ `session/cancel`-free wait for `turn/end` → `$events/result` answering any `approval/request`
waterfall → `/fs/list` → `/fs/upload` (multipart) → `/fs/download` full → `/fs/download` with
`Range` resume from a truncated local file → SHA-256 compare → `/gateway/logout` + revocation
check.

Exit codes: **0** all steps passed, **1** a step failed, **2** the gateway was unreachable.
Until the gateway exists you will get a clean exit 2 such as:

```
  [FAIL] CANNOT REACH THE GATEWAY: GET /gateway/health
        java.net.ConnectException: 拒绝连接
        connection refused - nothing is listening on http://127.0.0.1:3081
```

A normal turn finishes in well under the 2000 ms heartbeat interval, so the harness usually
never sees a server Ping. To actually exercise Ping to Pong, hold the socket open:

```bash
cd /root/project/remote-use/android
T=/root/project/face/android-sdk/.tooling
$T/jdk-17.0.20.1+1/bin/javac -encoding UTF-8 --release 8 -nowarn -d .pingbuild \
    $(find java/com/dsh/remote/net verify -name '*.java')
$T/jdk-17.0.20.1+1/bin/java -cp .pingbuild com.dsh.remote.verify.PingHoldCheck \
    --url http://127.0.0.1:3081 --user admin --pass 'your-password' --seconds 9
rm -rf .pingbuild
```

It exits 0 if the socket survives the idle hold. The server side (mock or real dsh) is what
reports the Ping/Pong counts.

---

## 5. What is VERIFIED vs NOT VERIFIED

Everything below was actually executed on this machine. Nothing is assumed.

### VERIFIED

| # | Claim | Evidence |
|---|---|---|
| 1 | `bash build_apk.sh` runs clean from scratch and prints the APK path | exit 0 on a run with `dist/` deleted (keystore regenerated). Log: `dist/build.log` |
| 2 | `verify_apk.py` passes the hard requirements | exit 0, `结论: 通过 ✓`; `resources.arsc` STORED at offset 91092 (`%4=0`), `classes.dex` STORED at 964 (`%4=0`) |
| 3 | `aapt2 dump badging` shows the required fields | `package: name='com.dsh.remote'`, `sdkVersion:'26'`, `targetSdkVersion:'34'`, `launchable-activity: name='com.dsh.remote.ui.LoginActivity'` |
| 4 | The APK is signed with v1+v2+v3 | `apksigner verify --verbose` → v2 true, v3 true; re-run with `--min-sdk-version 23` → **v1 true** as well (with `--min-sdk-version 26` apksigner simply skips v1 verification) |
| 5 | All 13 Java sources compile with the **real Android framework jar on the bootclasspath**, so every API used exists on Android | `javac -source 8 -target 8 -bootclasspath android.jar -classpath android.jar` → exit 0, 74 classes, zero warnings |
| 6 | `net/` has no Android dependency at all | the harness compiles it with `--release 8` and no `android.jar`; `grep '^import android\.' java/com/dsh/remote/net/` → 0 hits |
| 7 | The harness compiles clean on the JDK and fails *gracefully* when the gateway is absent | `HARNESS_EXIT=2` with `java.net.ConnectException: 拒绝连接` and a plain-English hint; no stack trace |
| 8 | The whole `net/` layer works end-to-end against a contract-conformant server | harness vs. the mock gateway: **16 PASS / 0 FAIL / 0 SKIP, exit 0**. Log: `verify-out/harness-against-mock.log` |
| 9 | Live token streaming actually works (`session/follow` → `assistant-stream` → `chunk`) | 7 chunks printed incrementally, 47 chars: `The answer is PONG-HARNESS and streaming works.` |
| 10 | Tool approvals are answered over plain HTTP | the mock blocked the turn on an `approval/request` waterfall; the harness answered `POST /api/$events/result` with `{"kind":"next"}` and the turn then completed (`turn/end`) |
| 11 | Upload/download are byte-correct, including **Range resume** | sha256 `f644c323c826f36eee9d536e003a4d08f87400534cb331a515b1ca7869341b27` identical for the uploaded file, the full download, and the file resumed from byte 32829 (206, 32830 of 65659 bytes) |
| 12 | The WebSocket client answers server Pings with **masked** Pongs and survives the 2000 ms heartbeat | `PingHoldCheck` held the socket 9 s: mock reported **pings sent 4 / pongs received 4**, `unmasked client frames: 0`, socket still open at the end. Log: `verify-out/mock-ping-own.log` |
| 13 | The dex contains the real app classes and protocol strings | string-table scan of `classes.dex`: all 4 activities + `WebSocketClient`/`DshRpcClient`, `/api/remote.mux`, `session/prompt`, `$events/result`, `client-request`, `Sec-WebSocket-Accept`, … |
| 14 | The RPC envelope details are right | the mock rejects `message.method != <ns>/<method>` with a business error; the harness passed every call, so the method field, the `{"args":…}` wrapper, the `rpcId` echo and the `result.ok` handling are all correct |
| 15 | `resources.arsc` is present (no "resource-less APK" failure mode from `LESSONS.md`) | `res/values/strings.xml` + `res/drawable/icon.png` are compiled into a real resource table |

### NOT VERIFIED — plain list

1. **No device and no emulator.** The APK has never been installed or launched. **Zero UI
   runtime testing**: no screen has ever been rendered, no button tapped, no `Activity`
   lifecycle exercised. Anything that only fails at runtime — a wrong view `LayoutParams`, a
   `NullPointerException` in an `onClick`, a `Toast` on a dead context — would not have been
   caught. This is the single biggest gap.
2. **Never run against the real `dsh-remote-gateway`.** At the time of verification its
   source tree (`dsh-remote-gateway/src/*.ts`) was still being written by another workstream
   and was not in a runnable state: it is a dsh *in-process* Cordis plugin (`ctx.connection`,
   `ctx.webServer`, a launch-token exchange with `127.0.0.1:3080`), so it cannot be started
   standalone — it needs a live `dsh` host. Booting one was out of scope here and would have
   collided with the workstream actively building it. Everything the harness proves is
   therefore against `tools/mock_gateway.py`, a test double written from
   `docs/GATEWAY-API.md` and `docs/dsh-transport-spec.md`. A mock can only ever be as right as the
   docs it was written from — **if the real gateway deviates from the frozen contract, this
   client is unverified against it.** Re-running `verify/run_verify.sh` against the real
   gateway is the single highest-value next check.
3. **Never run against real dsh over the real mux.** Docker/dsh live behaviour is unverified:
   whether real `snapshot` frames parse, whether real `assistant/message` payloads have the
   shape the renderer expects, whether dsh's `$events/result` really unblocks the turn, and
   whether the `approval/request` waterfall in practice carries the fields the dialog reads.
4. **No TLS testing.** Only plain `http://`/`ws://` was exercised. The `wss://` path
   (`SSLSocket` + SNI + certificate validation through a Cloudflare tunnel) is written but was
   never executed — there was no TLS endpoint to point it at. `Http.setInsecureTls(true)` was
   likewise never exercised.
5. **No fragmented-frame or large-frame testing from the server side.** The mock sent
   hand-built small frames. Real dsh may fragment, and the client's continuation-frame
   reassembly was verified only by unit-level reading and the RFC 6455 loopback test the
   implementer ran (small masked frames, Ping, Close echo) — not by a live long/fragmented
   stream.
6. **`session/cancel` is unverified.** The harness deliberately waits for `turn/end` instead,
   and the mock's cancel path was not exercised. The UI's Cancel button is untested.
7. **Chunked upload (mode B, `X-Upload-Id`/`X-Chunk-Index`) is not implemented.** Only
   multipart mode A is. Files beyond the gateway's `maxUploadBytes` therefore cannot be
   uploaded by this client.
8. **Downloads use app-private external storage, not the public Downloads collection.**
   `getExternalFilesDir(DIRECTORY_DOWNLOADS)` was chosen because it needs no runtime
   permission; the `MediaStore` route mentioned in the brief was not implemented, so files are
   not visible in the system Downloads app.
9. **The token is stored in plain `SharedPreferences` (MODE_PRIVATE), not Keystore-encrypted.**
   Deliberate: `android.security.keystore` encryption is straightforward in principle but was
   unreachable without a device to test it on, and an unverifiable crypto path is worse than a
   documented plain-text one. Anyone with root on the device (or a backup of the app data) can
   read the bearer token. `android:allowBackup="false"` is set to keep it out of cloud backups.
10. **No `res/` beyond one string and one PNG** — no per-density icons, no translations, no
    dark-theme resources, no `styles.xml`. The UI is built entirely in code and will look
    utilitarian.
11. **`net/`'s error branches are only partly exercised**: HTTP 401 on login and business
    errors (`result.ok == false`) were proven via the mock's negative-path runs, but the 429
    rate-limit branch, the 503 `not_configured` branch, 416/`path_denied` on the client side,
    and the gateway's reverse-proxy `/api/*` passthrough were not driven through the harness.
12. **No performance/soak testing.** One turn, one upload, one download. No long stream, no
    large file, no reconnect-after-drop scenario (the mux does reconnect lazily on the next
    `open()`, but that path was never exercised).

---

## 6. `tools/mock_gateway.py` — a TEST DOUBLE, not the gateway

To verify `net/` before the real gateway existed, a mock was written from the frozen docs:

```bash
python3 tools/mock_gateway.py --port 18081 --user admin --pass devpassword \
        --workspace /root/project/remote-use/android/verify-out/workspace
bash verify/run_verify.sh --url http://127.0.0.1:18081 --user admin --pass devpassword
```

It implements `docs/GATEWAY-API.md` (`/gateway/*`, `/fs/*`) and the `docs/dsh-transport-spec.md`
wire protocol (`/api/<ns>/<method>` envelopes, the `/api/remote.mux` mux, `session/follow`
with `assistantStream`, `$events` + `approval/request` waterfalls, a 2000 ms Ping heartbeat).

**It is not the gateway and proves nothing about the gateway.** It is a build-time test
fixture that lives entirely under `android/`. The real gateway is a dsh plugin owned by
another part of this project; its reverse-proxy, cookie-exchange, rate-limiting and TLS
behaviour are outside the scope of this double.

---

## 7. Why it looks like this

The target host is **aarch64 Kylin Linux** with no Gradle, no Kotlin compiler, no Android
Studio, no AndroidX, no Compose and no OkHttp jar, and installing them is out of scope. So:

* everything is `javac`-compiled plain Java 8 and dexed with `d8`;
* the build is a shell script driving `aapt2`/`zipalign` through `qemu-x86_64-static` (they are
  x86_64 binaries) and `d8`/`apksigner` through the native aarch64 JDK 17;
* the UI uses framework views built programmatically — no XML layouts, no AndroidX;
* `net/` uses `HttpURLConnection` and a hand-written RFC 6455 WebSocket client.

One consequence worth knowing: the build compiles with **`android.jar` on the javac
bootclasspath**. That is intentional — it makes `javac` reject any API that does not exist on
Android, which matters a lot when you cannot run the app on a device. The cost is that
`android.jar` has no `java.lang.invoke.LambdaMetafactory`, so **the source contains no lambdas
and no method references**; every callback is an anonymous inner class. Keep it that way or
the build will fail at step 3.
