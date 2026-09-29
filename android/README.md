# android/ — DSH Remote (plain-Java Android client)

An Android client for `dsh-remote-gateway`. It logs in to the gateway, lists/creates dsh
sessions, streams live assistant output over dsh's own WebSocket mux, answers tool-approval
prompts, and browses/uploads/downloads workspace files.

**The APK ships no third-party runtime dependency.** No Kotlin. No AndroidX. No Compose.
No OkHttp. No Gson. Plain Java (`-source 8 -target 8`) against Android framework APIs only.

There are **two builds of the same sources**, and they must agree:

| build | command | role |
|---|---|---|
| hand-rolled `aapt2 + d8 + apksigner` | `bash build_apk.sh` | the canonical pipeline; produces `dist/DshRemote-debug.apk` |
| Gradle + AGP | `./gradlew :app:assembleDebug` | added later, **only** so the UI layer can be executed under Robolectric |

Gradle/AndroidX exist here as **test-time tooling**, not as an app dependency. See
[§7 Why it looks like this](#7-why-it-looks-like-this) for the history and the rules
that keep the original design intact.

---

## 1. Layout

```
android/
├── build_apk.sh                       # hand-rolled build, re-runnable from scratch
├── gradlew                            # Gradle entry point (pins JDK 17 + repo-local toolchain)
├── settings.gradle, build.gradle      # root Gradle build (AGP 8.5.2)
├── gradle.properties                  # incl. android.aapt2FromMavenOverride (see §2)
├── local.properties                   # sdk.dir -> .toolchain/android-sdk (gitignored)
├── AndroidManifest.xml                # package com.dsh.remote, minSdk 26, targetSdk 34
├── res/values/strings.xml             # app_name
├── res/drawable/icon.png              # 96x96 launcher icon
├── app/
│   ├── build.gradle                   # module: points at ../java and ../res (NOT src/main)
│   └── src/test/java/com/dsh/remote/ui/
│       ├── UiTestBase.java            # lifecycle/looper/view helpers
│       ├── StubGateway.java           # loopback HTTP + WebSocket-upgrade test double
│       ├── LoginActivityTest.java
│       ├── SessionListActivityTest.java
│       ├── ChatActivityTest.java
│       └── FilesActivityTest.java
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
│       ├── App.java                   # SharedPreferences store (Keystore-encrypted token) + helpers
│       ├── TokenCipher.java           # AndroidKeyStore AES/GCM envelope + documented fallback
│       ├── Notify.java                # channels, event/ongoing notifications, foreground tracking
│       ├── GatewayWatchService.java   # foreground service: keeps the mux open, watches events
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
│   ├── setup_toolchain.sh             # rebuilds .toolchain/ (Gradle, SDK, JDK link)
│   └── mock_gateway.py                # TEST DOUBLE of the gateway (see §6) — not the gateway
├── dist/
│   ├── DshRemote-debug.apk            # the deliverable
│   └── build.log                      # log of a clean from-scratch build
├── verify-out/                        # harness + mock logs from the verification runs
└── SPEC.md                            # internal frozen interface spec used to build this
```

`net/` is deliberately Android-free: it compiles and runs on a desktop JDK 17, which is how
it is verified without a device. **The Gradle build must not break that** — no
Android/AndroidX artifact may reach the *main* compile classpath (see §7).

---

## 2. Build

### 2a. The hand-rolled pipeline (canonical)

```bash
cd /root/project/remote-use/android
bash build_apk.sh
```

Re-runnable from scratch: `rm -rf dist && bash build_apk.sh` also works and regenerates the
debug keystore. The script prints `APK: <absolute path>` as its final labelled line and exits
non-zero on any failure. It ends by running `tools/verify_apk.py` and `aapt2 dump badging`.

**Output: `/root/project/remote-use/android/dist/DshRemote-debug.apk`** (96.5 KB)

### 2b. The Gradle build

```bash
cd /root/project/remote-use/android
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk (58 KB)
./gradlew :app:testDebugUnitTest    # -> the Robolectric UI tests (§4)
```

`./gradlew` is **not** the stock wrapper. `/` is mounted read-only on this host, so
`~/.gradle`, `~/.android` and `/opt` cannot exist; the script pins everything to a toolchain
inside the repo:

```
.toolchain/gradle-8.7/     Gradle 8.7 distribution
.toolchain/gradle-home/    GRADLE_USER_HOME (dependency cache)
.toolchain/android-home/   ANDROID_USER_HOME (AGP's debug keystore + analytics)
.toolchain/android-sdk/    SDK: platforms/android-35 + build-tools/34.0.0
.toolchain/jdk-17          symlink -> /root/project/face/android-sdk/.tooling/jdk-17.0.20.1+1
```

`.toolchain/` is gitignored and 1.2 GB; `bash tools/setup_toolchain.sh` rebuilds it.

Three host-specific details are worth knowing:

* **`aapt2` and `zipalign` are x86_64 ELF binaries and this host is aarch64.** The SDK copy
  under `.toolchain/android-sdk/build-tools/34.0.0/` replaces those two with shell wrappers
  that exec the real binaries through `qemu-x86_64-static -L .../x86root`, exactly as
  `build_apk.sh` does. `gradle.properties` sets
  `android.aapt2FromMavenOverride` so AGP uses that wrapper instead of downloading its own.
  (`d8`/`apksigner` are Java jars and need no wrapper.)
* **`compileSdk 35` with AGP 8.5.2 prints an "untested compileSdk" warning.** It is a warning,
  not a failure; the platform jar is the same `android-35` the hand-rolled build uses.
* **The Gradle build signs with AGP's debug keystore, not `dist/debug.keystore`.** The two
  APKs therefore have *different* signers and cannot be installed over each other. Both are
  v1+v2+v3 signed (`signingConfig.enableV{1,2,3}Signing = true`).

The two builds are equivalent on the fields that matter:

```
# ./gradlew :app:assembleDebug
package: name='com.dsh.remote' versionCode='1' versionName='1.0' ... compileSdkVersion='35'
sdkVersion:'26'
targetSdkVersion:'34'
uses-permission: name='android.permission.INTERNET'
application-label:'DSH Remote'
application: label='DSH Remote' icon='res/drawable/icon.png'
application-debuggable
launchable-activity: name='com.dsh.remote.ui.LoginActivity'
```

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

## 4b. The Robolectric UI tests (this is how the UI layer is verified)

```bash
cd /root/project/remote-use/android
./gradlew :app:testDebugUnitTest --rerun-tasks
# -> BUILD SUCCESSFUL, 90 tests, 0 failures, 0 errors, 0 skipped   (2026-09-29)
# HTML report: app/build/reports/tests/testDebugUnitTest/index.html
```

The 90 are: 43 from the original UI suite, 13 more from the parallel work on
`FilesActivityTest` (9 → 16) and the new `ChunkedUploadTest` (6), and the **34 added for the
encrypted token store and notifications** (`TokenStoreTest` 11, `NotifyTest` 15,
`GatewayWatchServiceTest` 8 — §8).

Until these tests existed, **the UI layer had never been executed at all** — no screen
rendered, no button tapped, no `Activity` lifecycle run. These tests run the real Android
framework code (Activities, `LinearLayout`, `ListView`, `AlertDialog`, `SharedPreferences`,
the main `Looper`) on the JVM under Robolectric 4.14.1 at `sdk = 34`.

**Design rule: the Activities are NOT refactored and NOT mocked.** They build their network
client internally from `App`'s stored base URL, so the tests point that stored URL at a
loopback `StubGateway` (`StubGateway.java`) and let the **real** `net/` stack run —
`Http` → `HttpURLConnection` → a real socket → a real HTTP response, parsed by the real
`Json`/`GatewayClient`/`DshRpcClient`. `ChatActivity` even gets a genuine RFC 6455 upgrade
with a correctly computed `Sec-WebSocket-Accept`.

| test class | tests | what it drives |
|---|---|---|
| `LoginActivityTest` | 10 | form rendering; `performClick()` on Login with empty/partial fields (no request must be issued); 401 → error text, not a crash; unreachable server → error text; success → token stored + `SessionListActivity` started; valid stored token → auto-login; expired stored token → falls back to the form; full `create/start/resume/pause/stop/restart/destroy` chain |
| `SessionListActivityTest` | 10 | `session/list` → adapter row count **and rendered row text** (measure + layout, then read back); `onItemClick` → `ChatActivity` with the right `EXTRA_SESSION_ID`; "New session" `AlertDialog` → typed cwd reaches `session/create` → chat opens; create failure → status text; Refresh refetches; Logout clears `SharedPreferences` and returns to Login (also when the gateway is down); full lifecycle chain |
| `ChatActivityTest` | 14 | real mux WebSocket upgrade + both `session/follow` and `$events` opened on ONE socket; `assistant-stream` start/chunk/chunk/end rendered incrementally (`PONG-HARNESS`); a chunk outside a live section is dropped; `user/message`, `tool/call`, `tool/result`, `turn/end`, `snapshot` rendered; unknown/malformed frames ignored without crashing; `approval/request` waterfall → `AlertDialog` shown with Allow once / Reject buttons → Allow once posts `$events/result` `{"kind":"next"}` / Reject posts `{"kind":"rejected"}` with `error.code=user-rejected`; Send → `session/prompt` with the typed text; empty Send → nothing; Cancel turn → `session/cancel`; dead server → "stream open failed"; full lifecycle chain; destroy while streaming |
| `FilesActivityTest` | 16 | `/fs/list` → rows sorted directories-first with human sizes; Up disabled at root; tap directory → refetch with `path=docs` + breadcrumb/Up enabled; tap file → **bytes really written to disk** under `getExternalFilesDir`; dropped connection → "download failed"; 403 → pinned current (buggy) behaviour; Upload → `ACTION_OPEN_DOCUMENT`; picked document → **real multipart POST** whose body carries the bytes and the `path` field; full lifecycle chain |
| `TokenStoreTest` | 11 | token round-trips through the Keystore-encrypted store; **the plaintext is absent from the SharedPreferences XML on disk**; a legacy plaintext token is migrated on read and stays readable; every Keystore failure mode (write, migration, read, "encrypts but cannot decrypt") falls back to plaintext without throwing and without losing the session, with a reported reason; the real, unsubstituted AndroidKeyStore backend is exercised and shown to be unavailable here |
| `NotifyTest` | 15 | both channels created with the expected ids/importance; a turn completion posts a notification with the exact title/text on the event channel; an approval request posts one; tapping either opens that session's `ChatActivity` (unknown session → session list); **no notification for the session on screen** while a different session still notifies; the ongoing service notification is ongoing, low-importance and distinct; `POST_NOTIFICATIONS` denied → nothing posted, nothing thrown, state surfaced (Toast + ongoing-notification text) and the permission requested from the first Activity that resumes |
| `GatewayWatchServiceTest` | 8 | real `ServiceController` lifecycle (`create` → `startCommand` → `destroy`) with the ongoing notification attached/detached; `ACTION_STOP` → `stopSelf()`; the service stops itself when the token is gone; a dead gateway does not crash it; a `turn/end` frame pushed by the gateway posts the notification; an `approval/request` waterfall posts one whose tap opens the chat; **end-to-end suppression** with the session on screen vs. a control push for another session; the denied-permission path still runs the service |

Two host quirks are handled in the build rather than in the tests:

* `@ConscryptMode(Mode.OFF)` on every test class. Robolectric installs conscrypt as a JCE
  provider during setup, and `conscrypt-openjdk-uber:2.5.2` ships **no linux-aarch_64 native
  library**, so it dies with `UnsatisfiedLinkError`. Nothing here uses TLS to a real peer.
* `systemProperty 'user.home', .../.toolchain/robolectric-home` on the `Test` task.
  Robolectric takes a download lock at `$user.home/.robolectric-download-lock` and `/` is
  read-only.

`UiTestBase.waitFor(...)` is the other essential piece: the Activities do their I/O on a real
`ExecutorService` thread and marshal results back with `runOnUiThread`, and Robolectric's main
looper is **paused**. `waitFor` pumps `ShadowLooper.shadowMainLooper().idle()` in a loop until
a condition holds or a 20 s deadline expires. Without it every assertion would race the
background thread.

### Does it actually catch anything?

Yes — demonstrated, not asserted. Removing the `loginButton.setOnClickListener(...)` block
from `LoginActivity.buildUi()` (a change that **compiles, dexes and installs fine**, and which
no pre-existing check could have caught) turns the suite red:

```
com.dsh.remote.ui.LoginActivityTest > rendersTheLoginForm FAILED
    java.lang.AssertionError: Login button has no click listener
com.dsh.remote.ui.LoginActivityTest > clickingLoginWithEmptyFieldsDoesNotCrashAndIssuesNoRequest FAILED
    java.lang.AssertionError: performClick() reported no click listener
com.dsh.remote.ui.LoginActivityTest > clickingLoginWithUrlButNoUsernameIssuesNoRequest FAILED
    java.lang.AssertionError
com.dsh.remote.ui.LoginActivityTest > failedLoginShowsAnErrorInsteadOfCrashing FAILED
    java.lang.AssertionError: no /gateway/login request reached the stub
com.dsh.remote.ui.LoginActivityTest > successfulLoginStoresTheTokenAndGoesToTheSessionList FAILED
    java.lang.AssertionError: no /gateway/login request reached the stub
com.dsh.remote.ui.LoginActivityTest > unreachableServerIsReportedNotThrown FAILED
    java.lang.AssertionError: expected a 'Login failed' status line, tree text was:
    | DSH Remote | http://127.0.0.1:42089 | admin | x | Login | DSH Remote

43 tests completed, 6 failed
```

The change was then reverted and `git diff`-equivalent comparison confirmed the file was
byte-identical to the pre-break version; the suite went back to 43/43.

---

## 5. What is VERIFIED vs NOT VERIFIED

Everything below was actually executed on this machine. Nothing is assumed.

> **This section was originally written before the gateway existed**, when the only server
> available was the mock. It has since been revised twice. Items 2, 3 and 4 below are RESOLVED
> by runs against the **real** gateway (locally and through a real Cloudflare edge). And the
> single biggest gap — "the UI layer has never been executed" — is now **partially** closed by
> the Robolectric suite in §4b (VERIFIED items 18–26), with the precise remainder spelled out
> in NOT VERIFIED item 13. The remaining gaps are the ones that genuinely still stand.

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
| 16 | The whole `net/` layer works end-to-end against the **real** `dsh-remote-gateway` + real `dsh` | `scripts/verify-all.sh` step 5: **15/15, exit 0**. Drives real `session/list` → `session/create` → real model streaming → real upload/download + Range resume |
| 17 | The same, over **public `https://` + `wss://`** through a real Cloudflare edge | `scripts/e2e-public.sh`: **15/15** against a `trycloudflare.com` Quick Tunnel; real `SSLSocket` + SNI + certificate validation |
| 18 | **The UI layer is now actually EXECUTED.** All four Activities go through the real Android lifecycle and real click dispatch on the JVM | `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL, 43 tests, 0 failures, 0 skipped**. Report: `app/build/reports/tests/testDebugUnitTest/index.html` |
| 19 | The tests **can fail**, i.e. they are not vacuous | deleting `loginButton.setOnClickListener(...)` from `LoginActivity.buildUi()` turned 6 tests red (`Login button has no click listener`, `performClick() reported no click listener`, `no /gateway/login request reached the stub`, …), then the file was restored byte-identically and the suite went back to 43/43 |
| 20 | A `ListView` row is not just *counted* but **rendered and read back** | `SessionListActivityTest` / `FilesActivityTest` call `measure()` + `layout()` on each row and assert on the resulting text: `docs/ directory`, `a.txt 1 KB`, `sess-001…  ● running  /work/alpha`, directory-first sort |
| 21 | A download really writes bytes to disk under Robolectric | `tappingAFileDownloadsItOntoDisk`: `getExternalFilesDir(DIRECTORY_DOWNLOADS)/a.txt` exists and its content equals the stub's body |
| 22 | A picked document really becomes a multipart upload | `pickingADocumentUploadsItsBytesAndName`: real `ContentResolver` → provider → `Http.uploadFile`; the recorded request body carries the picked bytes and `name="path"` |
| 23 | The real `net/` stack runs inside the UI tests (no mocking of production code) | the Activities' stored base URL is pointed at a loopback `StubGateway`; every request is a real socket + real `HttpURLConnection`; `ChatActivity` completes a real RFC 6455 upgrade (`Sec-WebSocket-Accept` computed by the stub) |
| 24 | The Gradle build compiles the **same** sources and produces an equivalent APK | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`; badging matches items 3 above (`com.dsh.remote`, 26/34/35, same launcher activity); `apksigner verify --min-sdk-version 23` → v1+v2+v3 true, same as item 4 |
| 25 | Adding Gradle did **not** break the verified pipeline | `bash build_apk.sh` → exit 0, `结论: 通过 ✓`, `APK: /root/project/remote-use/android/dist/DshRemote-debug.apk` |
| 26 | Adding Gradle did **not** pollute `net/` | `grep '^import android\.' java/com/dsh/remote/net/` → 0 hits; `javac --release 8` with **no `android.jar`** on the classpath → exit 0, 26 classes; `verify/run_verify.sh` still exits **2** (gateway not running) with the graceful message |
| 27 | The bearer token is stored **encrypted** and the plaintext is not on disk | `TokenStoreTest.tokenRoundTripsThroughTheEncryptedStore` + `plaintextIsAbsentFromTheSharedPreferencesXmlAfterAWrite`: after `App.saveLogin(...)`, the real `shared_prefs/dsh_remote.xml` contains `token_enc` and `v1:` and **does not contain the token string** |
| 28 | A plaintext token from an older build is migrated without logging the user out | `TokenStoreTest.preExistingPlaintextTokenIsMigratedAndStillReadable` (11 tests in that class) |
| 29 | Every Keystore failure degrades to the old plaintext behaviour, explicitly | `TokenStoreTest` (5 failure-mode tests, incl. the unsubstituted real backend) — no exception, token still readable, reason recorded in `TokenCipher.lastFallbackReason()` and logged |
| 30 | Notifications work end-to-end while the app is backgrounded | `GatewayWatchServiceTest`: a real foreground service, real mux upgrade, frames pushed by the stub gateway → `turn/end` and `approval/request` notifications with the expected title/text/channel/tap target |
| 31 | The event for the session on screen is suppressed, and only that one | `NotifyTest.noNotificationIsPostedForTheSessionOnScreen` + `GatewayWatchServiceTest.noNotificationForTheSessionCurrentlyOnScreen` (control push for a second session still notifies) |
| 32 | These 34 new tests are **not vacuous** | 15 deliberate breaks (encryption removed, migration disabled, fallback removed, write-verification removed, channel id/importance, turn title/text, approval wiring/text, suppression, tap target, permission check, `startForeground`/`stopForeground`) each turned the corresponding test(s) red; every production file was then restored and `cmp`-verified byte-identical. Full transcript in §8.4 |

### NOT VERIFIED — plain list

1. ~~**No device and no emulator.** The APK has never been installed or launched. **Zero UI
   runtime testing**: no screen has ever been rendered, no button tapped, no `Activity`
   lifecycle exercised.~~ **PARTIALLY RESOLVED — see the new item 13 below for what Robolectric
   still does not cover.** The Activities now run under Robolectric: the real framework code
   executes, views are built, `performClick()` dispatches real clicks, the lifecycle chains
   `create/start/resume/pause/stop/restart/destroy` run, `AlertDialog`s are shown and answered,
   and list rows are measured, laid out and read back. **What that does NOT prove** is that the
   app works on a real device: Robolectric *shadows* a great deal (see item 13).
2. ~~**Never run against the real `dsh-remote-gateway`.**~~ **RESOLVED.** The gateway has since
   been completed and this harness now runs against it for real — `scripts/verify-all.sh` boots
   a real `dsh` + the plugin and runs `verify/run_verify.sh` against it as a mandatory step.
   Result: **15/15 against the real gateway** (the 16th mock-only step, `$events/result answered
   an approval`, does not fire because a real turn does not necessarily raise one). Also run
   against the real gateway **through a real Cloudflare edge** by `scripts/e2e-public.sh` → 15/15
   over public HTTPS/WSS. The mock (`tools/mock_gateway.py`) is retained only as an offline
   fixture; see §6.
3. ~~**Never run against real dsh over the real mux.**~~ **RESOLVED.** Real `dsh` now answers over
   the real `/api/remote.mux`: the harness opens `$events` + `session/follow`, drives
   `session/list` → `session/create` → `session/prompt`, receives real `assistant-stream` chunks
   (live model text `PONG-HARNESS`), and gets a real `turn/end`. See the root `README.md`
   "验证状态" for the full transcript. What remains unverified from this item: whether the
   `approval/request` waterfall carries the fields the dialog reads (no real turn has raised one
   in these runs), and `snapshot` frame parsing.
4. ~~**No TLS testing.**~~ **RESOLVED.** `scripts/e2e-public.sh` puts a real Cloudflare Quick
   Tunnel in front of the gateway and the harness then runs the whole flow over
   **`https://` + `wss://`**, so `SSLSocket` + SNI + certificate validation were actually
   executed against a real certificate chain. Still unexercised: `Http.setInsecureTls(true)`
   (deliberately — it only exists for self-signed test gateways, which these runs do not use).
5. **No fragmented-frame or large-frame testing from the server side.** The mock sent
   hand-built small frames. Real dsh may fragment, and the client's continuation-frame
   reassembly was verified only by unit-level reading and the RFC 6455 loopback test the
   implementer ran (small masked frames, Ping, Close echo) — not by a live long/fragmented
   stream.
6. ~~**`session/cancel` is unverified.**~~ **PARTIALLY RESOLVED.** `ChatActivityTest.cancelButtonPostsSessionCancel`
   taps the real Cancel button and asserts a real `session/cancel` request carrying the right
   `sessionId` is posted. Still unverified: that the *server* honours it and that the turn
   actually stops — the stub only records the call, and no real `dsh` turn has been cancelled.
7. **Chunked upload (mode B, `X-Upload-Id`/`X-Chunk-Index`) is not implemented.** Only
   multipart mode A is. Files beyond the gateway's `maxUploadBytes` therefore cannot be
   uploaded by this client.
8. **Downloads use app-private external storage, not the public Downloads collection.**
   `getExternalFilesDir(DIRECTORY_DOWNLOADS)` was chosen because it needs no runtime
   permission; the `MediaStore` route mentioned in the brief was not implemented, so files are
   not visible in the system Downloads app.
9. ~~**The token is stored in plain `SharedPreferences` (MODE_PRIVATE), not Keystore-encrypted.**~~
   **RESOLVED — see §8.1.** The token is now encrypted with an AndroidKeyStore AES/GCM key
   (`TokenCipher`, preference `token_enc`), legacy plaintext tokens are migrated on read, and
   the plaintext copy is deleted once the encrypted blob has been verified to decrypt back.
   What remains *unverified* is the crypto on a **real device**: Robolectric has no
   AndroidKeyStore provider, so the AndroidKeyStore key generation/encryption path itself has
   never executed anywhere — only the envelope/migration/fallback logic around it has (with the
   key provider substituted in tests, §8.4) and the "Keystore unavailable" path has (for real).
   `android:allowBackup="false"` is still set, so the token stays out of cloud backups.
10. **No `res/` beyond one string and one PNG** — no per-density icons, no translations, no
    dark-theme resources, no `styles.xml`. The UI is built entirely in code and will look
    utilitarian.
11. **`net/`'s error branches are only partly exercised**: HTTP 401 on login and business
    errors (`result.ok == false`) were proven via the mock's negative-path runs, but the 429
    rate-limit branch, the 503 `not_configured` branch, 416/`path_denied` on the client side,
    and the gateway's reverse-proxy `/api/*` passthrough were not driven through the harness.
    (The Robolectric tests do now cover a 401 on login, a 500 on `session/list`, a 503 on
    `/fs/list`, a business error on `session/create`, and a dropped TCP connection — but still
    not 429 or 416.)
12. **No performance/soak testing.** One turn, one upload, one download. No long stream, no
    large file, no reconnect-after-drop scenario (the mux does reconnect lazily on the next
    `open()`, but that path was never exercised).
13. **Robolectric is not a device.** This is the honest boundary of the new UI tests. What they
    do **not** establish:
    * **No real rendering.** Nothing is rasterised and no pixel is drawn; `measure()`/`layout()`
      run the real `View` code but there is no `Surface`, no `Canvas`, no GPU, no density or
      screen-size variation, no font metrics. A layout that is *logically* right but visually
      broken would pass.
    * **No real touch input.** `performClick()` / `performItemClick()` invoke the listeners
      directly. The `View` touch pipeline (`onInterceptTouchEvent` → `onTouchEvent` → slop,
      long-press, scroll-vs-click arbitration) is not exercised, so a `ListView` row that is
      unclickable in practice because an ancestor eats the touch would still pass.
    * **No animations, no `Choreographer` frames, no real timing.** `ShadowLooper.idle()` runs
      queued work immediately; anything that depends on real elapsed time, frame callbacks,
      `postDelayed` ordering or `View.post` after a layout pass behaves differently.
    * **A long list of framework calls is shadowed rather than executed.** `SharedPreferences`,
      `Toast`, `Looper`/`Handler` scheduling, `ContentResolver`, the `Activity`/`Window` stack,
      `ActivityThread` and the manifest/package manager are Robolectric shadows. The tests prove
      the *app's* code path, not the platform's.
    * **No `WebView`, no `MediaStore`, no runtime permissions, no process death/recreation, no
      configuration change (`android:configChanges` / rotation), no multi-window, no
      `savedInstanceState` restoration, no background/foreground transitions, no `Intent`
      resolution against a real `PackageManager`.** (The notification work added two more
      shadowed areas: `NotificationManager` is a shadow, so nothing is drawn and no channel
      importance is ever *enforced* by the system; and **there is no AndroidKeyStore provider
      at all**, so the real Keystore crypto path cannot run under Robolectric — §8.4 explains
      exactly which part is substituted and which part is real.)
    * **No real server.** The tests run against `StubGateway`, a hand-written test double. It
      computes a correct `Sec-WebSocket-Accept`, but it is not `dsh` and proves nothing about
      `dsh`. (The `net/` harness in §4 is what covers the real gateway; the two are
      complementary.)
    * **TLS is disabled for the test JVM** (`@ConscryptMode(OFF)`), so no HTTPS/WSS path runs in
      these tests at all.
14. ~~**Two real bugs were found while writing the tests and are NOT fixed**~~ **BOTH FIXED.** The
    Robolectric suite found two genuine defects in the existing client. Both are now fixed and
    the tests that used to *pin the broken behaviour* now *assert the correct behaviour*:
    * **A non-2xx download was silently reported as success.** `Http.doDownload` only opens the
      destination file for a 2xx status and `GatewayClient.download` never inspected
      `Response.status`, so a `403 path_denied` left **no file at all** while the UI still
      showed `Saved: <path>` and raised a `Toast`. **Fix:** `GatewayClient.download` now throws
      a `GatewayException` on any non-2xx (with a readable reason per status: 403 `path_denied`,
      404 `not_found`, 401 `unauthorized`, 416 `range_not_satisfiable`), so the UI reports
      `download failed: …`. Asserted by
      `FilesActivityTest.nonTwoHundredDownloadIsReportedAsFailure`.
    * **The multipart `filename` was a cache temp name, not the picked name.** `FilesActivity.upload`
      copies the picked document to `cacheDir/upload-<millis>` and handed that `File` to
      `GatewayClient.upload`, so `Http.uploadFile` sent `filename="upload-1790661938194"`. The
      correct display name *was* sent in the separate `path` field, so the server-side result was
      right — but a server (or log, or downstream tool) keying off the multipart filename would
      record the wrong name. **Fix:** added `Http.uploadFile(relPath, file, filenameOverride)`
      and `GatewayClient.upload(relPath, file, filename)`; `FilesActivity` passes the sanitized
      display name. Asserted by `FilesActivityTest.pickingADocumentUploadsItsBytesAndName`.

    Both fixes were verified by re-running the whole suite: **43/43 pass**, and both APK
    pipelines still build (`bash build_apk.sh` → `结论: 通过 ✓`; `./gradlew :app:assembleDebug`
    → `BUILD SUCCESSFUL`).

15. **Real background push is NOT implemented, and cannot be from inside this repository.**
    Notifications are produced by a foreground service inside the app process (§8.2), so they
    stop when the process is gone: **force-stop, "clear all", a device reboot before the app is
    opened again, or an aggressive ROM killing the service**. Covering that needs FCM (a
    Firebase project + `google-services.json` + a server that can send) or a self-hosted
    UnifiedPush distributor such as ntfy (a public/self-hosted server + the app subscribing and
    publishing its endpoint to the gateway). Both need external infrastructure that only the
    user can supply; §8.3 lists exactly what.

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

The target host is **aarch64 Kylin Linux**. When the app was written it had no Gradle, no
Kotlin compiler, no Android Studio, no AndroidX, no Compose and no OkHttp jar, and installing
them was out of scope. So:

* everything is `javac`-compiled plain Java 8 and dexed with `d8`;
* the build is a shell script driving `aapt2`/`zipalign` through `qemu-x86_64-static` (they are
  x86_64 binaries) and `d8`/`apksigner` through the native aarch64 JDK 17;
* the UI uses framework views built programmatically — no XML layouts, no AndroidX;
* `net/` uses `HttpURLConnection` and a hand-written RFC 6455 WebSocket client.

One consequence worth knowing: the build compiles with **`android.jar` on the javac
bootclasspath**. That is intentional — it makes `javac` reject any API that does not exist on
Android, which matters a lot when you cannot run the app on a device. The cost is that
`android.jar` has no `java.lang.invoke.LambdaMetafactory`, so **the source under `java/`
contains no lambdas and no method references**; every callback is an anonymous inner class.
Keep it that way or `build_apk.sh` will fail at step 3.

(The **test** sources under `app/src/test/java/` are different: they are compiled by AGP for
the JVM only and use lambdas freely. That is not an inconsistency — they never go near
`android.jar`'s bootclasspath, and they are not part of the APK.)

### Why Gradle was added later, and what it is allowed to touch

Gradle and AndroidX were authorised later for exactly one reason: **the UI layer had never been
executed**, and there is no `/dev/kvm` on this host, so an emulator is impossible. Robolectric
runs the real framework on the JVM and is the only way to close that gap here. Two rules keep
that from eroding the original design:

1. **`net/` stays pure.** No Android/AndroidX artifact may reach the *main* compile classpath.
   The check is mechanical: `grep '^import android\.' java/com/dsh/remote/net/` must stay empty,
   and `verify/run_verify.sh` must still compile `net/` with `--release 8` and **no `android.jar`
   at all**. Both are re-run after any Gradle change.
2. **`build_apk.sh` stays canonical.** It is what `scripts/verify-all.sh` calls, and it is what
   produces `dist/DshRemote-debug.apk`. The Gradle build is additive.

Gradle's own footprint is confined to `.toolchain/` (gitignored) and `android/app/build/`.

---

## 8. Encrypted token store and background notifications (added 2026-09-29)

Two things changed here: the bearer token is no longer plaintext on disk (§8.1), and the app
now notifies about the two events worth waking up for while it is backgrounded (§8.2). §8.3 is
the honest boundary — **this is not push** — and §8.4 is the evidence, including 15 deliberate
breaks that each turned the new tests red.

### 8.1 The bearer token is Keystore-encrypted (`TokenCipher`, `App`)

* **Key** — alias `dsh_remote_token_v1` in the **AndroidKeyStore**, AES-256, GCM, no padding,
  `setUserAuthenticationRequired(false)` (the token must be readable without unlocking the
  screen), `setRandomizedEncryptionRequired(true)` (a fresh IV per encryption). Generated on
  first use by `TokenCipher.key()`; it never leaves the Keystore, the app only holds a handle.
* **Stored blob** — preference `token_enc` holds
  `"v1:" + base64( IV(12 bytes) ‖ ciphertext‖tag(16 bytes) )`. The version prefix is checked on
  read; anything else is refused rather than guessed at. The plain `token` preference is
  **removed** once the encrypted copy is stored.
* **Write** (`App.saveLogin`) — encrypt → **decrypt it back to verify** → only then store the
  blob and drop the plaintext. The verification exists because a ROM whose Keystore encrypts
  but cannot decrypt would otherwise destroy the stored session on the next read.
* **Read** (`App.getToken`) — decrypt `token_enc`; if that is impossible, fall back to the
  plaintext copy if this install still has one (and try to migrate it); otherwise return `""`,
  which makes `LoginActivity` show the login form instead of crashing.
* **Migration** — a plaintext `token` left by an older build is encrypted and removed on the
  first read. The user is **not** logged out by the upgrade, and the migration is idempotent
  (once per process, guarded by `TokenCipher.isKnownUnusable()` so a broken ROM is not
  re-probed on every single store access).
* **The fallback triggers on exactly these four conditions** (all of them are *documented*, not
  silent):
  1. `encrypt` throws anything at all → plaintext write, reason `write: <cause>`;
  2. the blob does not decrypt back → plaintext write, reason `write: verify: <cause>`;
  3. a legacy plaintext token exists and `encrypt` throws during migration → the plaintext is
     **left in place**, reason `migrate: <cause>`;
  4. a `token_enc` blob cannot be decrypted on read → the plaintext copy is used if present,
     reason `read: <cause>`.
  Every fallback writes one `Log.w("DshRemote", "token store: falling back to plain
  SharedPreferences: …")` per distinct reason, and `App.tokenStorageMode(ctx)` reports
  `"keystore"` / `"plaintext"` / `"empty"` so the state is inspectable rather than a rumour.
* **API compatibility** — the signatures the Activities use (`saveLogin`, `clearLogin`,
  `getBaseUrl`, `getUsername`, `getToken`, `getExpiresAt`, `rpc`) are unchanged; only the
  storage behind them changed.

### 8.2 Notifications (`Notify`, `GatewayWatchService`)

| piece | detail |
|---|---|
| channels | `dsh_remote_events` — "Agent events", `IMPORTANCE_DEFAULT`; `dsh_remote_service` — "Background watching", `IMPORTANCE_LOW`, no badge. Two channels on purpose: the mandatory ongoing notification must not buzz. |
| service | `GatewayWatchService`, `android:foregroundServiceType="dataSync"`, `exported="false"`; started by `App.saveLogin` and by `Notify.ensureWatching()` from the first Activity that resumes with a stored token; stopped by `App.clearLogin` (logout) or `ACTION_STOP`; `START_STICKY`; the watch loop stops the service itself when the token is gone. |
| what it watches | `$events` (stream `watch-events`) for `waterfall` / `approval/request`; `session/follow` with `assistantStream:false` for every session `session/list` reports as `running` (polled every 5 s, at most 4 concurrently) for the durable `turn/end` event. |
| approval → notification | title **"Approval needed"**, text `Tool <toolName> is waiting for your approval[: <reason>]`. This is the valuable one: the host turn is blocked until it is answered. |
| turn end → notification | title **"Turn finished"**, text `Session <shortId> - turn <n> ended (<reason>)`. |
| tapping | `ChatActivity` with `EXTRA_SESSION_ID` for that session; when no session is known, `SessionListActivity`. |
| suppression | `Notify` tracks the **resumed** `ChatActivity`'s session id through `Application.ActivityLifecycleCallbacks` (registered from `App`'s store accessors, so no Activity had to be edited). An event whose session is the one on screen, while the app is in the foreground, is not notified — that screen already shows it. A turn ending in a *different* session still notifies: dsh can run several. |
| approval's session hint | the `$events` frame carries an `agentId`, not a session id (and `session/list` has no `agentId`), so the hint is the last chat the user opened, else the single followed session, else unknown. This is a real limitation, stated rather than papered over. |
| ongoing notification | id `1`, `setOngoing(true)`, channel `dsh_remote_service`, tap → session list. Its text is `Watching for approvals and turn completions`, or `Notifications are off - grant the notification permission` when they are. |
| `POST_NOTIFICATIONS` | requested once from the first Activity that resumes on API 33+ (`requestPermissions`). If it is refused, nothing crashes and nothing is silently dropped: no event notification is posted, a Toast says `Notifications are off - approval alerts will not appear` (once per process), logcat gets a warning, and the ongoing notification says so too. `Notify.notificationsEnabled()` checks `checkSelfPermission(POST_NOTIFICATIONS)` **and** `areNotificationsEnabled()`; the explicit permission check is not redundant — it is what makes the denied case behave correctly here, because `areNotificationsEnabled()` still reports `true` while the runtime permission is denied (measured under Robolectric; `NotifyTest.postNotificationsDeniedDoesNotCrashAndIsSurfaced` pins it). |

### 8.3 What this does and does NOT cover, and what real push would need

**Covered** (all in-process): the app is in the background, the screen is off, the Activities
have been destroyed, a turn is running on the host — the foreground service keeps the mux open
and posts the notification. This is the main use case ("I started a turn and put the phone
down"). It was verified end-to-end under Robolectric against a real WebSocket and real frames
(§8.4).

**Not covered — and it cannot be, without external infrastructure:**

* **the app process is gone** — force-stop from Settings, "Clear all" in Recents on some ROMs,
  or the service being killed by an aggressive ROM;
* **device rebooted** — nothing runs until the app is opened again (there is no
  `BOOT_COMPLETED` receiver, and adding one would still not give you a running mux without a
  persistent service);
* **Doze / App Standby battery restrictions** on some OEM builds can still delay or drop the
  service even though it is a foreground service.

Those cases need **real push**, where the *server* wakes the device. Two options:

1. **FCM.** What the user must supply:
   * a Firebase project with an Android app registered under the package name
     `com.dsh.remote`, and the resulting **`google-services.json`** placed at
     `android/app/google-services.json`;
   * the `com.google.gms.google-services` Gradle plugin **and** the Firebase Messaging
     dependency — neither is available in this offline toolchain, and the hand-rolled
     `build_apk.sh` has no plugin system at all, so it would additionally need the FCM AAR's
     `classes.jar` on its `javac` classpath and in its `d8` inputs;
   * **server-side credentials** (a Firebase service-account JSON or an HTTP v1 OAuth token)
     for `dsh-remote-gateway`, plus a new plugin route for the app to register its FCM
     registration token, and a send call on `approval/request` / `turn/end`;
   * the payload must carry the session id, otherwise the tap target degrades to §8.2's
     "unknown session" path.
2. **UnifiedPush (self-hosted, e.g. ntfy).** What the user must supply: an ntfy server
   (public or self-hosted), the UnifiedPush connector library (again not available offline),
   an endpoint registration route on the gateway, and a publish call from the gateway on the
   same two events.

Neither is stubbed or faked anywhere in this repository: there is **no** `FirebaseMessagingService`,
no `google-services.json`, no FCM dependency, and no code that pretends a push arrived. The root
`README.md`'s "推送通知未实现" item remains accurate for *push*; what was added is the
foreground-service + local-notification path described above.

### 8.4 Evidence, and proof that the new tests can fail

```
$ cd /root/project/remote-use/android && ./gradlew :app:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL in 34s          # exit 0
```

| test class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `TokenStoreTest` | 11 | 0 | 0 | 0 |
| `NotifyTest` | 15 | 0 | 0 | 0 |
| `GatewayWatchServiceTest` | 8 | 0 | 0 | 0 |
| the other five classes | 56 | 0 | 0 | 0 |
| **whole suite** | **90** | **0** | **0** | **0** |

**The Keystore substitution, stated plainly.** Robolectric has no `AndroidKeyStore` provider
(`KeyStore.getInstance("AndroidKeyStore")` → `KeyStoreException: AndroidKeyStore not found`),
so the *key provider* is substituted in the encrypted-path tests (`JceTokenBackend`, a JVM
AES/GCM provider producing the identical `IV‖ciphertext‖tag` envelope). Everything else is
production code: the envelope and version prefix, the storage keys, the write-verification, the
migration, the fallback and the reporting. The **unsubstituted** backend is exercised too —
`TokenStoreTest.realAndroidKeyStoreIsUnavailableHereSoTheRealFallbackRuns` asserts that the
real code path reports the real failure and still keeps the session. What no test here can
establish is that AndroidKeyStore key generation/encryption works on a real device: that
requires a device (see NOT VERIFIED 9).

**16 deliberate breaks, each reverted byte-identically** (every file restored with `cp` from a
saved copy and then verified with `cmp`):

| # | what was broken | tests that went red |
|---|---|---|
| 1 | encryption removed from the write path | 5 (`tokenRoundTripsThroughTheEncryptedStore` → `expected:<keystore> but was:<plaintext>`; `plaintextIsAbsentFromTheSharedPreferencesXmlAfterAWrite`; …) |
| 2 | transparent migration disabled | 3 (`preExistingPlaintextTokenIsMigratedAndStillReadable`, `migrationIsIdempotentAcrossReads`, `keystoreFailureDuringMigrationKeepsThePlaintextCopy`) |
| 3 | Keystore fallback removed (rethrow) | 3 (`IllegalStateException: the Keystore failed and there is no fallback`) |
| 4 | write-verification removed | 1 (`aBackendThatCannotDecryptItsOwnBlobIsNotUsedForStorage` → token read back as `""`) |
| 5 | event channel importance → `LOW` | 1 (`expected:<3> but was:<2>`) |
| 5b | event channel id renamed | 1 (`expected:<dsh_remote_events> but was:<dsh_remote_events_v2>`) |
| 6 | turn-completion title changed | 2 (`expected:<Turn [finished]> but was:<Turn [done]>`) |
| 6b | turn-completion text changed | 6 (`…turn 3[ ended (completed])` vs `…turn 3[])`) |
| 7 | `$events` approval branch not wired in the service | 1 (`no approval notification arrived`) |
| 7b | approval text changed | 3 |
| 8 | foreground-session suppression disabled | 2 (`the session on screen must not be notified expected:<0> but was:<2>`) |
| 8c | notification tap no longer deep-links | 3 (`expected:<…ChatActivity> but was:<…SessionListActivity>`) |
| 9 | `POST_NOTIFICATIONS` no longer consulted | 2 (`the POST_NOTIFICATIONS runtime permission must be consulted`) |
| 10 | `startForeground()` removed | 1 (`startForeground() was never called`) |
| 10b | `stopForeground()` removed | 1 (`the ongoing notification must be removed on destroy`) |
| 11 | the "notifications are off" surfacing removed | 1 (`the denied state was never surfaced`) |

The APK was rebuilt afterwards with the canonical pipeline and still passes:

```
$ bash build_apk.sh
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_DATA_SYNC'
结论: 通过 ✓ （硬性条件全部满足）
APK: /root/project/remote-use/android/dist/DshRemote-debug.apk
```
