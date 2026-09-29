# dsh browser-transport protocol — evidence-backed spec

**Ground truth root:** `/root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh/`
(hereafter `$DSH`). Packages live at
`$DSH/node_modules/@deepseek-ai/<pkg>/` (hereafter `$PKG/<pkg>/`).

**Version verified:** originally `$DSH/package.json` → `"version": "0.1.5-rc.3"`.
Re-checked against `"0.1.7-rc.2"`: the browser-transport surface this spec covers
(webserver routes, the `/api` trust fence, browser-auth cookie exchange,
`REMOTE_STREAM_MUX_PATH`) is **unchanged**; see `PLUGIN-NOTES.md` §8 for the
line-by-line re-verification and the few unrelated deltas.

> Every claim below cites an absolute path and a verbatim quote from a file that was
> actually read. Negative results are stated explicitly. Where a claim could not be
> established from the installed code, it says so.

---

## 1. API PATH

### `API_PATH`

`$PKG/dsh-client-connection/lib/types/api-path.d.ts:6`

```ts
export declare const API_PATH = "/api";
```

Confirmed in the compiled bundle, `$PKG/dsh-client-connection/lib/index.js:13`:

```js
const API_PATH = "/api";
```

Doc comment at `$PKG/dsh-client-connection/lib/types/api-path.d.ts:5`:
`/** Route prefix owning every api request (`/api` and `/api/<anything>`). */`

### What the host mounts, and where

`$PKG/dsh-client-connection/lib/index.js:767-781` — one **prefix** route on the
web server:

```js
const fetchHandler = connection.createSharedFetchHandler(API_PATH);
const route = {
    kind: "prefix",
    path: API_PATH,
    handler: async (req, res) => {
        const rejection = connection.requestRejection(req);
        if (rejection !== void 0) {
            res.writeHead(rejection);
            res.end(rejection === 401 ? "unauthorized" : "forbidden");
            return;
        }
        await bridge(req, res, fetchHandler, maxRequestBodyBytes);
    }
};
webCtx.effect(() => webCtx.webServer.register(route), "client-connection: /api route");
```

`$PKG/dsh-host-webserver/lib/index.js:322-331` defines the matching rule
(`kind: "prefix"` matched by longest-prefix-wins; `exact` by verbatim pathname).

Inside `/api`, dispatch is a **two-table lookup** —
`$PKG/dsh-client-connection/lib/index.js:570-586`:

```js
createSharedFetchHandler(channel) {
    return {
        requestBodyMode: ({ method, url }) => {
            const route = this.fetchRoutes.get(url.pathname);
            return route?.methods.has(method) === true ? route.requestBody : "buffered";
        },
        fetch: (request) => {
            const pathname = new URL(request.url).pathname;
            const route = this.fetchRoutes.get(pathname);
            if (route?.methods.has(request.method) === true) return route.fetch(request);
            const endpoint = endpointFromPath(channel, pathname);
            const interceptor = this.interceptors.get(channel);
            if (endpoint === void 0 || interceptor === void 0 || !interceptor.matches(endpoint)) return Promise.resolve(new Response("not found", { status: 404 }));
            return interceptor.fetchHandler.fetch(request);
        }
    };
}
```

So a request to `/api/...` is routed to, in order:
1. an **exact fetch route** keyed by full pathname (`ctx.connection.fetch.register`), else
2. the **RPC interceptor** for the channel (the Typert gateway), else
3. `404 "not found"`.

### The RPC carrier's HTTP method contract

`$PKG/dsh-client-connection/lib/index.js:635-663` (`rpcFetchHandler`):

```js
async fetch(request) {
    const endpoint = endpointFromPath(channel, new URL(request.url).pathname);
    if (request.method !== "POST" || endpoint === void 0) return new Response("not found", { status: 404 });
    if (request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase() !== "application/json") return new Response("content type must be application/json", { status: 415 });
    let body;
    try {
        body = await request.json();
    } catch {
        return new Response("body is not JSON", { status: 400 });
    }
    const envelope = clientRequestSchema.safeParse(body);
    ...
```

**POST only. `content-type: application/json` required** (415 otherwise). Body must be JSON (400 otherwise).

### Transport verdict (Q1)

The browser transport is **two physical carriers**, not one:

| carrier | path | methods | purpose |
|---|---|---|---|
| HTTP | `/api/<namespace>/<method>` (prefix route) | **POST** only for RPC; a few exact fetch routes use GET/HEAD/POST | every **unary** Remote call, plus `$events/result` replies, plus file upload/download routes |
| **WebSocket** | `/api/remote.mux` (exact upgrade route) | HTTP `Upgrade` | **every streaming** Remote call (`session/follow`, `session/control`, `workspace/follow`, `workspaceFiles/changes`) and the `$events` forwarded-event firehose |
| SSE | `/plugins/events` (exact route, **different plugin**) | GET/HEAD | dev-only client-plugin HMR reload feed — **not** part of the RPC transport |

There is **no SSE in the RPC transport**. There is exactly **one** WebSocket.

---

## 2. RPC FRAMING

### The declared envelopes

`$PKG/dsh-client-connection/lib/types/rpc.d.ts:43-57`:

```ts
/** Full request envelope carried by Connection RPC transports. */
export interface ClientRequest {
    readonly type: 'client-request';
    readonly rpcId: RpcId;
    readonly method: string;
    readonly payload: unknown;
}
/** Full response envelope carried by Connection RPC transports. */
export interface ServerResponse {
    readonly type: 'server-response';
    readonly rpcId: RpcId;
    readonly result: ConnectionRpcResult<unknown>;
}
/** Complete Connection RPC envelope union. */
export type RpcMessage = ClientRequest | ServerResponse;
```

`$PKG/dsh-client-connection/lib/types/rpc.d.ts:11-24` (failure + result):

```ts
/** Carrier-neutral failure returned by one logical RPC endpoint. */
export interface ConnectionRpcFailure {
    readonly code: string;
    readonly message: string;
    readonly details: object;
}
/** Carrier-neutral result returned by one logical RPC endpoint. */
export type ConnectionRpcResult<T> = {
    readonly ok: true;
    readonly value: T;
} | {
    readonly ok: false;
    readonly error: ConnectionRpcFailure;
};
```

### `RpcId`

`$PKG/dsh-client-connection/lib/types/rpc.d.ts:3-10`:

```ts
/** Correlation id minted by a caller and echoed by the Connection response. */
export type RpcId = Branded<'rpc-id'>;
/**
 * Brand one validated string as a Connection correlation id.
 * @param id - validated wire identity.
 * @returns the same string with the correlation-id brand.
 */
export declare function RpcId(id: string): RpcId;
```

The brand is **compile-time only** — `$PKG/dsh-client-connection/lib/index.js:458-460`:

```js
function RpcId(id) {
	return id;
}
```

### The runtime schemas (zod v4)

`$PKG/dsh-client-connection/lib/rpc-schema.d.ts` (complete) and the compiled body at
`$PKG/dsh-client-connection/lib/index.js:479-515`:

```js
const rpcIdSchema = z$1.string();
const rpcErrorSchema = z$1.object({
	code: z$1.string(),
	message: z$1.string(),
	details: z$1.record(z$1.string(), z$1.unknown())
});
function rpcResultSchema(value) {
	return z$1.union([z$1.object({
		ok: z$1.literal(true),
		value
	}), z$1.object({
		ok: z$1.literal(false),
		error: rpcErrorSchema
	})]);
}
const clientRequestSchema = z$1.object({
	type: z$1.literal("client-request"),
	rpcId: rpcIdSchema,
	method: z$1.string(),
	payload: z$1.unknown()
});
const serverResponseSchema = z$1.object({
	type: z$1.literal("server-response"),
	rpcId: rpcIdSchema,
	result: rpcResultSchema(z$1.unknown().optional())
});
const rpcMessageSchema = z$1.discriminatedUnion("type", [clientRequestSchema, serverResponseSchema]);
```

**`rpcId` is any non-empty-or-empty string as far as the wire validator is concerned** —
`rpcIdSchema = z.string()`, no format/UUID check. The *client* mints a UUIDv4
(`$PKG/dsh-client-connection/lib/client.js:6199`).

### The client's exact call (round trip)

`$PKG/dsh-client-connection/lib/client.js:6194-6223`:

```js
function createWebConnectionRpc(doFetch, openStream) {
    const send = doFetch ?? ((input, init) => globalThis.fetch(input, init));
    return {
        async call(channel, endpoint, payload, signal) {
            assertTarget(channel, endpoint);
            const rpcId = RpcId(randomUuid());
            const message = {
                type: "client-request",
                rpcId,
                method: endpoint,
                payload
            };
            const response = await send(new URL(`${channel}/${endpoint}`, resolveBase()), {
                method: "POST",
                headers: { "content-type": "application/json" },
                body: JSON.stringify(message),
                ...signal === void 0 ? {} : { signal }
            });
            if (!response.ok) throw new Error(`transport failure for ${channel}/${endpoint}: HTTP ${response.status}`);
            const full = parseConnectionResponse(await response.json());
            if (full.rpcId !== rpcId) throw new Error(`rpcId mismatch for ${endpoint}: sent ${rpcId}, got ${full.rpcId}`);
            return full.result;
        },
        ...
```

Round trip:
1. Caller does `rpc.call("/api", "session/list", { args: {...} })`.
2. Client mints `rpcId = <uuid v4>`, builds `{type:"client-request", rpcId, method:"session/list", payload:{args:{...}}}`.
3. `POST http://<authority>/api/session/list`, `content-type: application/json`.
4. Host validates the envelope, then requires `message.method === endpoint` — `$PKG/dsh-client-connection/lib/index.js:651-655`:
   `if (message.method !== endpoint) return errorResponse(message.rpcId, { code: "gateway/bad-request", message: \`method ${JSON.stringify(message.method)} does not match endpoint ${JSON.stringify(endpoint)}\`, details: { issues: [] } });`
5. Handler runs; response is `Response.json({type:"server-response", rpcId, result})` — `$PKG/dsh-client-connection/lib/index.js:685-692`.
6. Client requires `response.ok` (**any** non-2xx throws), parses the envelope, checks `full.rpcId === rpcId`, and returns `result` (`{ok:true,value}` or `{ok:false,error}`).

**Note:** every business failure is HTTP **200** with `result.ok === false`. Only carrier-level faults (403 fence, 401 auth, 415 content-type, 400 bad JSON, 413 too large, 500 handler throw) use a non-2xx status.

### Streaming framing (Q2 last part)

Streaming is **NOT** chunked HTTP responses and **NOT** SSE. Streaming is
**WebSocket text frames multiplexed by `streamId`** on `/api/remote.mux`.

`$PKG/dsh-api-gateway/lib/types/stream-protocol.d.ts`:

```ts
/** Exact WebSocket route carrying every Typert Remote stream. */
export declare const REMOTE_STREAM_MUX_PATH = "/api/remote.mux";
...
/** One logical stream request sent from the browser. */
export type RemoteStreamClientMessage = {
    readonly type: 'open';
    readonly streamId: string;
    readonly endpoint: string;
    readonly payload: unknown;
} | {
    readonly type: 'cancel';
    readonly streamId: string;
};
/** One logical stream frame sent from the Host. */
export type RemoteStreamServerMessage = {
    readonly type: 'item';
    readonly streamId: string;
    readonly value?: unknown;
} | {
    readonly type: 'error';
    readonly streamId: string;
    readonly error: RemoteStreamFailure;
} | {
    readonly type: 'end';
    readonly streamId: string;
};
```

Host pump, `$PKG/dsh-api-gateway/lib/index.js:322-345`:

```js
async pump(streamId, endpoint, payload, active) {
    try {
        const source = await this.open(endpoint, payload, active.abort.signal);
        for await (const value of source) await this.send({ type: "item", streamId, value });
        if (!active.abort.signal.aborted) await this.send({ type: "end", streamId });
    } catch (error) { ... this.send({ type: "error", streamId, error: this.failure(error) }) ... }
```

Binary frames are refused — `$PKG/dsh-api-gateway/lib/index.js:287-291`:
`this.socket.close(1003, "text messages required")`.
Bad JSON closes with `1008, "invalid Remote stream request"` (`:295`).

**Streaming payload wrapper:** the same `{ args: {...} }` object used by unary calls.
Client side, `$PKG/dsh-api-gateway/lib/client.js:1636`:
`const stream = this.openRemoteStream(endpoint, { args: prepared.args }, prepared.signal);`

---

## 3. CHANNELS AND ENDPOINTS

### 3a. Logical RPC **channels** — there is exactly ONE

`$PKG/dsh-client-connection/lib/types/rpc.d.ts:104-120` declares
`HostConnectionRpc.handle(channel, handler)` for registering extra channels. **In the
whole installed tree, `rpc.handle(` is called ZERO times** (grepped across every
`*.js` and `*.d.ts`). The only interceptor registration is
`$PKG/dsh-api-gateway/lib/index.js:455`:

```js
connectionCtx.connection.rpc.intercept("/api", (endpoint) => this.claimsEndpoint(endpoint), (endpoint, payload, signal) => this.dispatchRpc(endpoint, payload, signal));
```

`/api` is reserved and cannot be `handle`d — `$PKG/dsh-client-connection/lib/index.js:694`:
`if (!CHANNEL_PATTERN.test(channel) || channel === "/api") throw new Error(\`connection: invalid or reserved RPC channel ...\`)`.

**Conclusion: the only logical channel is `/api`.** The prompt's "many channels" framing
is wrong; there is one channel carrying a dynamically-claimed endpoint namespace.

### 3b. How endpoints are claimed

`$PKG/dsh-api-gateway/lib/index.js:508-514`:

```js
claimsEndpoint(endpoint) {
    if (endpoint === "$events/result") return true;
    const segments = endpoint.split("/");
    if (segments.length !== 2 || segments[0] === "" || segments[1] === "") return false;
    if (this.ctx.typert.local.get(endpoint) !== void 0 || this.ctx.typert.local.hasSeen(endpoint)) return true;
    this.srcClaims ??= this.collectSrcClaims();
    return this.srcClaims.has(endpoint);
}
```

**An endpoint is exactly `<namespace>/<method>` — two segments, no more.** Endpoint
names come from generated `typert.host.js` / `typert.remote-client.js` descriptor
manifests (`/* Generated by @deepseek-ai/dsh-typert-generator from FaceModel — do not edit. */`),
emitted per package and exported via the package's `./typert` and `./remote` subpaths.

### 3c. EXHAUSTIVE endpoint table — **84 endpoints**, 4 streaming

Extracted by importing all 16 generated `lib/typert.host.js` manifests and reading
`TYPERT.invocations` directly (no inference). Counts: 84 total, 4 with `mode: 'stream'`.

Per package: `dsh-api-session-controller` 18, `dsh-cordis-host-runner` 12,
`dsh-api-settings-controller` 10, `dsh-api-workspace-controller` 10,
`dsh-api-workspace-files` 7, `dsh-goal` 7, `dsh-agent-presets` 5, `dsh-llm` 3,
`dsh-message-feedback` 3, `dsh-subagent` 3, `dsh-commands` 2, `dsh-client-file-upload` 1,
`dsh-command-feedback` 1, `dsh-host-plugin-inventory` 1, `dsh-session-reference` 1.

**The four streaming endpoints** (all other 80 are unary HTTP POST):
`session/control`, `session/follow`, `workspace/follow`, `workspaceFiles/changes`.

Plus **two gateway-internal** endpoints on the same surfaces:

| endpoint | transport | payload | owning code |
|---|---|---|---|
| `$events` | **WS mux** (`{type:'open',streamId,endpoint:"$events",payload:{args:{}}}`) | frames `{type:'ready',clientId,host:{home}}`, `{type:'emit',event,args}`, `{type:'waterfall',event,eventId,agentId,request}`, `{type:'cancel',eventId}` | `$PKG/dsh-api-gateway/lib/index.js:582-585`, `:600-612` |
| `$events/result` | **HTTP POST** `/api/$events/result` | `{args:{clientId,eventId,outcome}}` | `$PKG/dsh-api-gateway/lib/index.js:567-578`; client `$PKG/dsh-api-gateway/lib/client.js:678` |

The gateway rejects a stream endpoint on the unary channel and vice versa —
`$PKG/dsh-api-gateway/lib/index.js:540` (`"stream Remote methods must be opened through the stream carrier"`)
and `:555` (`"unary Remote methods cannot be opened through the stream carrier"`).

| # | endpoint (wire string) | transport | args (wire fields) | result | owning package |
|---|---|---|---|---|---|
| 1 | `agentPresets/copy` | HTTP POST | from: string, id: string, name: any \\| string | any | `dsh-agent-presets` |
| 2 | `agentPresets/deletePreset` | HTTP POST | id: string | any | `dsh-agent-presets` |
| 3 | `agentPresets/list` | HTTP POST | *(none)* | {presets: {id: string, trust: 'system' \| 'user', isDefault: boolean, name?: string, description?: string, broken?: string}[], authorable: boolean} | `dsh-agent-presets` |
| 4 | `agentPresets/read` | HTTP POST | agentPreset: string | {agentPreset: string, trust: 'system' \| 'user', content: string, name?: string, description?: string} | `dsh-agent-presets` |
| 5 | `agentPresets/select` | HTTP POST | agentId: string *(lookup agent)*, agentPreset: string | string | `dsh-agent-presets` |
| 6 | `commands/execute` | HTTP POST | agentId: string *(lookup agent)*, line: string, submittedAttachments: {type: 'image', mediaType: 'image/png' \| 'image/jpeg' \| 'image/webp' \| 'im… | any \\| {commandId: string, result: {kind: 'success', text?: string, sourceEventSeq?: number} \| {kind: 'error', text: string}} | `dsh-commands` |
| 7 | `commands/list` | HTTP POST | agentId: string *(lookup agent)* | {name: string, description: string, input?: {hint: string, attachments?: boolean}}[] | `dsh-commands` |
| 8 | `credentials/describe` | HTTP POST | refs: string[] | object | `dsh-api-settings-controller` |
| 9 | `credentials/set` | HTTP POST | ref: string, value: string | any | `dsh-api-settings-controller` |
| 10 | `credentials/unset` | HTTP POST | ref: string | any | `dsh-api-settings-controller` |
| 11 | `directoryPicker/createDirectory` | HTTP POST | path: string, name: string | string | `dsh-api-workspace-controller` |
| 12 | `directoryPicker/list` | HTTP POST | path: any \\| string | {path: string, home: string, crumbs: {name: string, path: string, hidden: boolean}[], entries: {name: string, path: string, hidden: boolean}[], tru… | `dsh-api-workspace-controller` |
| 13 | `directoryPicker/pick` | HTTP POST | *(none)* | None \\| string | `dsh-api-workspace-controller` |
| 14 | `dynamicCordisRunner/getClientCode` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string, pluginRunId: string | {code: string, name: string, pluginId: string, packageId: string, pluginRunId: string} | `dsh-cordis-host-runner` |
| 15 | `dynamicCordisRunner/inventory` | HTTP POST | *(none)* | {pluginId: string, agentId: string, packages: {packageId: string, name: string, purpose: string, hasHostHalf: boolean, hasClientHalf: boolean}[], c… | `dsh-cordis-host-runner` |
| 16 | `dynamicCordisRunner/invoke` | HTTP POST | pluginId: string, pluginRunId: string, method: string, args: None \\| string \\| number \\| False \\| True \\| JsonValue[] \\| object | {ok: True, value: None \| string \| number \| False \| True \| JsonValue[] \| object} \\| {ok: False, code: 'plugin-not-running' \| 'stale-run' \| … | `dsh-cordis-host-runner` |
| 17 | `dynamicCordisRunner/reportClientGuardFailure` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string, pluginRunId: string, failure: {message: string, stack?: string} | None | `dsh-cordis-host-runner` |
| 18 | `dynamicCordisRunner/reportRenderFailure` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string, pluginRunId: string, failure: {slot: string, message: string, stack?: string, abdicated: boolean} | None | `dsh-cordis-host-runner` |
| 19 | `dynamicCordisRunner/resolveInspectQuery` | HTTP POST | agentId: string *(lookup agent)*, requestId: string, resolution: {ok: True, data: None \| string \| number \| False \| True \| JsonValue[] \| objec… | {accepted: boolean} | `dsh-cordis-host-runner` |
| 20 | `dynamicCordisRunner/resolveRequestRun` | HTTP POST | requestId: string, resolution: {ok: True, pluginRunId: string, waitingFor?: string[]} \\| {ok: False, reason: 'rejected' \| 'host-half-failed' \| '… | {accepted: boolean} | `dsh-cordis-host-runner` |
| 21 | `dynamicCordisRunner/runHostHalf` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string, packageId: string, mode: 'run' \\| 'update', requestId: None \\| string, approveFutureVersions:… | {ok: True, pluginId: string, packageId: string, pluginRunId: string, waitingFor: string[], startedHere: boolean} \\| {ok: False, message: string, s… | `dsh-cordis-host-runner` |
| 22 | `dynamicCordisRunner/settleUserRun` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string, resolution: {ok: True, pluginRunId: string, waitingFor?: string[]} \\| {ok: False, reason: 'rej… | {ok: True, status: 'running' \| 'awaiting-approval' \| 'starting', pluginId: string, packageId: string, pluginRunId: string, waitingFor: string[], … | `dsh-cordis-host-runner` |
| 23 | `dynamicCordisRunner/stopFromPanel` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string | {ok: True} \\| {ok: False, reason: 'plugin-missing' \| 'not-running', message: string} | `dsh-cordis-host-runner` |
| 24 | `dynamicCordisRunner/syncInspectManifest` | HTTP POST | providers: {id: string, description: string, methods: {name: string, description: string, inputSchema: None \| string \| number \| False \| True \|… | None | `dsh-cordis-host-runner` |
| 25 | `dynamicCordisRunner/undefineFromPanel` | HTTP POST | agentId: string *(lookup agent)*, pluginId: string | {ok: True, wasRunning: boolean} \\| {ok: False, reason: 'plugin-missing', message: string} | `dsh-cordis-host-runner` |
| 26 | `fileReferences/list` | HTTP POST | agentId: string *(lookup agent)*, query: string | {path: string, kind: 'file' \| 'directory'}[] | `dsh-api-session-controller` |
| 27 | `fileUploads/upload` | HTTP POST | agentId: string *(lookup agent)*, request: {data: string, name?: string} | {receiptId: string, file: {attachmentId: string, name: string, bytes: number}} | `dsh-client-file-upload` |
| 28 | `goals/clear` | HTTP POST | agentId: string *(lookup agent)*, ref: {id: string, revision: number} | {id: string, revision: number} | `dsh-goal` |
| 29 | `goals/complete` | HTTP POST | agentId: string *(lookup agent)*, ref: {id: string, revision: number} | {roundsStarted: number, createdAt: number, updatedAt: number, activation: 'armed' \| 'disarmed', objective: string, phase: 'active' \| 'paused' \| … | `dsh-goal` |
| 30 | `goals/create` | HTTP POST | agentId: string *(lookup agent)*, request: {objective: string, maxGoalRounds?: number} | {ref: {id: string, revision: number}} | `dsh-goal` |
| 31 | `goals/edit` | HTTP POST | agentId: string *(lookup agent)*, ref: {id: string, revision: number}, request: {objective?: string, maxGoalRounds?: number} | {roundsStarted: number, createdAt: number, updatedAt: number, activation: 'armed' \| 'disarmed', objective: string, phase: 'active' \| 'paused' \| … | `dsh-goal` |
| 32 | `goals/get` | HTTP POST | agentId: string *(lookup agent)* | any \\| {roundsStarted: number, createdAt: number, updatedAt: number, activation: 'armed' \| 'disarmed', objective: string, phase: 'active' \| 'pau… | `dsh-goal` |
| 33 | `goals/pause` | HTTP POST | agentId: string *(lookup agent)*, ref: {id: string, revision: number} | {roundsStarted: number, createdAt: number, updatedAt: number, activation: 'armed' \| 'disarmed', objective: string, phase: 'active' \| 'paused' \| … | `dsh-goal` |
| 34 | `goals/resume` | HTTP POST | agentId: string *(lookup agent)*, ref: {id: string, revision: number} | {roundsStarted: number, createdAt: number, updatedAt: number, activation: 'armed' \| 'disarmed', objective: string, phase: 'active' \| 'paused' \| … | `dsh-goal` |
| 35 | `llm/discoverModels` | HTTP POST | settingsNs: string, request: {provider?: string, baseURL?: string, api?: string, apiKey?: string} | {id: string, name?: string, contextWindow?: number, maxTokens?: number}[] | `dsh-llm` |
| 36 | `llm/listConfigurableProviders` | HTTP POST | *(none)* | {provider: string, displayName: string, settingsNs: string, settingsPath: string[], declared?: boolean, error?: string}[] | `dsh-llm` |
| 37 | `llm/listProviders` | HTTP POST | *(none)* | {id: string, name: string}[] | `dsh-llm` |
| 38 | `messageFeedback/delete` | HTTP POST | request: {sessionId: string, messageId: string, ifVersion: string} | {ok: True, value: {absent: True}} \\| {ok: False, error: {code: 'session-not-found', sessionId: string} \| {code: 'version-conflict', current: None… | `dsh-message-feedback` |
| 39 | `messageFeedback/list` | HTTP POST | request: {sessionId: string} | {ok: True, value: {items: {messageId: string, rating: 'positive' \| 'negative', note?: string, category?: 'other' \| 'task-result' \| 'instruction-… | `dsh-message-feedback` |
| 40 | `messageFeedback/put` | HTTP POST | request: {sessionId: string, messageId: string, rating: 'positive' \| 'negative', note?: string, category?: 'other' \| 'task-result' \| 'instructio… | {ok: True, value: {messageId: string, rating: 'positive' \| 'negative', note?: string, category?: 'other' \| 'task-result' \| 'instruction-followin… | `dsh-message-feedback` |
| 41 | `pluginInventory/list` | HTTP POST | *(none)* | {entries: {entryId: string, moduleName: string, enabled: boolean, fiberPhase: None \| 'pending' \| 'active' \| 'failed' \| 'loading' \| 'unloading'… | `dsh-host-plugin-inventory` |
| 42 | `session/attachment` | HTTP POST | request: {sessionId: string, attachmentId: string} | {attachment: {attachmentId: string, mediaType: 'image/png' \| 'image/jpeg' \| 'image/webp' \| 'image/gif', bytes: number, width: number, height: nu… | `dsh-api-session-controller` |
| 43 | `session/canOpenWorkspacePath` | HTTP POST | *(none)* | boolean | `dsh-api-session-controller` |
| 44 | `session/cancel` | HTTP POST | request: {sessionId: string} | {accepted: True} | `dsh-api-session-controller` |
| 45 | `session/control` | **WS mux** | *(none)* | {type: 'baseline', value: {queues: object, jobs: object, projections: object}} \\| {type: 'queue', sessionId: string, items: {id: string, placement… | `dsh-api-session-controller` |
| 46 | `session/create` | HTTP POST | request: {workspaceId?: string, cwd?: string, sessionId?: string, agentPreset?: string} | {sessionId: string, agentPreset?: string} | `dsh-api-session-controller` |
| 47 | `session/follow` | **WS mux** | request: {address: {kind: 'session', sessionId: string} \| {kind: 'subagent', parentSessionId: string, childSessionId: string, mode: 'one-shot' \| … | {type: 'event', event: {type: string, seq: number, time: number, data: None \| string \| number \| False \| True \| JsonValue[] \| object, ignorabl… | `dsh-api-session-controller` |
| 48 | `session/fork` | HTTP POST | request: {sessionId: string, atSeq?: number} | {sessionId: string} | `dsh-api-session-controller` |
| 49 | `session/list` | HTTP POST | _request: {cursor?: string} | {items: {sessionId: string, updatedAt: number, running: boolean, blank: boolean, parentSessionId?: string, origin?: 'subagent', cwd?: string, proje… | `dsh-api-session-controller` |
| 50 | `session/modelCatalog` | HTTP POST | *(none)* | {default: {provider: string, model: string, reasoningEffort?: string}, routableProviders: string[], groups: {id: string, name: string, models: {id:… | `dsh-api-session-controller` |
| 51 | `session/openWorkspacePath` | HTTP POST | request: {action?: 'reveal', path: string} | {opened: True} | `dsh-api-session-controller` |
| 52 | `session/page` | HTTP POST | request: {address: {kind: 'session', sessionId: string} \| {kind: 'subagent', parentSessionId: string, childSessionId: string, mode: 'one-shot' \| … | {records: {type: 'event', event: {type: string, seq: number, time: number, data: None \| string \| number \| False \| True \| JsonValue[] \| object… | `dsh-api-session-controller` |
| 53 | `session/prompt` | HTTP POST | request: {requestId: string, sessionId: string, mode: 'queue' \| 'steer', content: {type: 'text', text: string} \| {type: 'image', mediaType: 'imag… | {accepted: True} | `dsh-api-session-controller` |
| 54 | `session/rename` | HTTP POST | request: {sessionId: string, title: string} | {title: string, seq: number} | `dsh-api-session-controller` |
| 55 | `session/search` | HTTP POST | request: {query: string} | {items: {sessionId: string, snippet: string}[], hasMore: boolean} | `dsh-api-session-controller` |
| 56 | `session/selectModel` | HTTP POST | request: {sessionId: string, provider: string, model: string, reasoningEffort?: string} | {selected: {provider: string, model: string, reasoningEffort?: string}} | `dsh-api-session-controller` |
| 57 | `session/updateQueue` | HTTP POST | request: {sessionId: string, itemId: string, action: {kind: 'edit', content: {type: 'file', attachment: {attachmentId: string, name: string, bytes:… | {accepted: True} | `dsh-api-session-controller` |
| 58 | `sessionFeedback/record` | HTTP POST | request: {sessionId: string, text?: string, category?: 'other' \| 'task-result' \| 'instruction-following' \| 'product-interaction' \| 'service-sta… | {ok: True, value: {recorded: True}} \\| {ok: False, error: {code: 'session-not-found', sessionId: string}} | `dsh-command-feedback` |
| 59 | `sessionReferenceResolver/candidates` | HTTP POST | agentId: string *(lookup agent)*, query: string | {mention: string, sessionId: string, label: string, cwd?: string, sameWorkspace: boolean, createdAt: number}[] | `dsh-session-reference` |
| 60 | `settings/canOpenAgentPresetDirectory` | HTTP POST | *(none)* | boolean | `dsh-api-settings-controller` |
| 61 | `settings/describe` | HTTP POST | *(none)* | {writable: boolean, hasDocument: boolean, namespaces: {ns: string, schema: None \| string \| number \| False \| True \| JsonValue[] \| object, valu… | `dsh-api-settings-controller` |
| 62 | `settings/mutate` | HTTP POST | ns: string, ops: {op: 'set', path: string[], value: None \| string \| number \| False \| True \| JsonValue[] \| object} \| {op: 'unset', path: stri… | {ns: string, schema: None \| string \| number \| False \| True \| JsonValue[] \| object, value: None \| string \| number \| False \| True \| JsonVa… | `dsh-api-settings-controller` |
| 63 | `settings/openAgentPresetDirectory` | HTTP POST | agentPreset: string | {opened: True} \\| {opened: False, path: string} | `dsh-api-settings-controller` |
| 64 | `settings/openSettingsDocument` | HTTP POST | *(none)* | {opened: True} | `dsh-api-settings-controller` |
| 65 | `settings/replace` | HTTP POST | ns: string, section: object, expectedRevision: any \\| number | {ns: string, schema: None \| string \| number \| False \| True \| JsonValue[] \| object, value: None \| string \| number \| False \| True \| JsonVa… | `dsh-api-settings-controller` |
| 66 | `settings/update` | HTTP POST | ns: string, patch: object, expectedRevision: any \\| number | {ns: string, schema: None \| string \| number \| False \| True \| JsonValue[] \| object, value: None \| string \| number \| False \| True \| JsonVa… | `dsh-api-settings-controller` |
| 67 | `skills/list` | HTTP POST | request: {sessionId: string} | {skills: {name: string, description: string, whenToUse?: string, modelInvocable: boolean}[]} | `dsh-api-session-controller` |
| 68 | `subagents/interruptByParent` | HTTP POST | childSessionId: string, parentSessionId: string, mode: 'continuable' | {accepted: True} | `dsh-subagent` |
| 69 | `subagents/list` | HTTP POST | parentSessionId: string | {entries: {kind: 'child', id: string, activity: 'running' \| 'inactive', hasChildren: boolean, mode: 'one-shot', label?: string} \| {kind: 'child',… | `dsh-subagent` |
| 70 | `subagents/prompt` | HTTP POST | request: {requestId: string, parentSessionId: string, childSessionId: string, mode: 'continuable', delivery: 'queue' \| 'steer', content: {type: 't… | {messageId: string} | `dsh-subagent` |
| 71 | `workspace/archiveSession` | HTTP POST | request: {sessionId: string} | {archivedSessionIds: string[]} | `dsh-api-workspace-controller` |
| 72 | `workspace/create` | HTTP POST | request: {path: string} | {workspace: {workspaceId: string, path: string, title: string, sessionIds: string[], createdAt: string, updatedAt: string}, created: boolean} | `dsh-api-workspace-controller` |
| 73 | `workspace/delete` | HTTP POST | request: {workspaceId: string} | {deleted: True} | `dsh-api-workspace-controller` |
| 74 | `workspace/follow` | **WS mux** | *(none)* | {type: 'baseline', value: {items: {workspaceId: string, path: string, title: string, sessionIds: string[], createdAt: string, updatedAt: string}[],… | `dsh-api-workspace-controller` |
| 75 | `workspace/insertBefore` | HTTP POST | request: {workspaceId: string, beforeWorkspaceId?: string} | {workspaceIds: string[]} | `dsh-api-workspace-controller` |
| 76 | `workspace/insertSessionBefore` | HTTP POST | request: {workspaceId: string, sessionId: string, beforeSessionId?: string} | {workspace: {workspaceId: string, path: string, title: string, sessionIds: string[], createdAt: string, updatedAt: string}} | `dsh-api-workspace-controller` |
| 77 | `workspace/rename` | HTTP POST | request: {workspaceId: string, title: string} | {workspace: {workspaceId: string, path: string, title: string, sessionIds: string[], createdAt: string, updatedAt: string}} | `dsh-api-workspace-controller` |
| 78 | `workspaceFiles/changes` | **WS mux** | workspaceFileScopeId: string *(lookup workspaceFileScope)* | {kind: 'ready'} \\| {kind: 'change', change: {absolutePath: string, version: string} \| {absolutePath: string, absent: True}} | `dsh-api-workspace-files` |
| 79 | `workspaceFiles/list` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string | {path: string, entries: {name: string, type: 'file' \| 'directory' \| 'other', size?: number}[], truncated: boolean} | `dsh-api-workspace-files` |
| 80 | `workspaceFiles/read` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string, range: {offset?: number, limit?: number} | {offset: number, text: string, lines: number, eof: boolean, absolutePath: string, version: string, bytes?: number} | `dsh-api-workspace-files` |
| 81 | `workspaceFiles/readAll` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string | {offset: number, data: string, eof: boolean, absolutePath: string, version: string, bytes?: number} | `dsh-api-workspace-files` |
| 82 | `workspaceFiles/readBytes` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string, range: {offset?: number, length?: number} | {offset: number, data: string, eof: boolean, absolutePath: string, version: string, bytes?: number} | `dsh-api-workspace-files` |
| 83 | `workspaceFiles/readRelated` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string, relativePath: string | {offset: number, data: string, eof: boolean, absolutePath: string, version: string, bytes?: number} | `dsh-api-workspace-files` |
| 84 | `workspaceFiles/stat` | HTTP POST | workspaceFileScopeId: string *(lookup workspaceFileScope)*, path: string | {absolutePath: string, version: string, bytes?: number} | `dsh-api-workspace-files` |

### 3d. HTTP routes registered directly on the web server (outside `/api`)

| path | kind | methods | package | fence? |
|---|---|---|---|---|
| `/api` | prefix | all → RPC/fetch dispatch | `dsh-client-connection` | **yes** (`requestRejection`) |
| `/plugins` | prefix | GET/HEAD | `dsh-client-modules` | no |
| `/plugins/events` | exact | GET/HEAD (**SSE**) | `dsh-client-hmr` | no |
| `/open-in-app/apps` | exact | GET | `dsh-host-open-in-app` | yes |
| `/open-in-app/icon` | prefix | GET | `dsh-host-open-in-app` | yes |
| `/open-in-app/open` | exact | POST | `dsh-host-open-in-app` | yes |
| `config.path` (runtime) | exact | POST | `dsh-webhook-github` | HMAC signature instead |
| *(fallback seat)* | fallback | GET/HEAD | `dsh-host-frontend-static` | index only (`authorizeIndex`) |
| `/api/remote.mux` | **upgrade** | Upgrade | `dsh-api-gateway` | **yes** (`requestRejection`) |

**Exact fetch sub-routes behind the `/api` prefix** (`ctx.connection.fetch.register`):

| path | methods | requestBody | package |
|---|---|---|---|
| `/api/file` | GET, HEAD | buffered | `dsh-api-session-controller` |
| `/api/session/uploadFileBinary` | POST | **streaming** | `dsh-client-file-upload` |
| `/api/present.host` | GET | buffered | `dsh-client-ui-deliverables` |
| `/api/present.open` | POST | buffered | `dsh-client-ui-deliverables` |
| `/api/session.export` | GET, HEAD | buffered | `dsh-session-log-export` |

**Totals:** 8 web-server route registrations (7 named + 1 fallback) + 1 upgrade route +
5 exact fetch sub-routes + **84 dynamic Typert endpoints** + 2 gateway-internal endpoints.

---

## 4. EVENT STREAM

Live agent activity reaches the browser over **two distinct mechanisms**, both on the
same WebSocket.

### 4a. `session/follow` — the durable session event stream (WS mux)

Streaming endpoint `session/follow`, `mode: 'stream'`. Frame union, from
`$PKG/dsh-api-session-controller/lib/typert.host.js` (the generated declaration of
`SessionFollowFrame`):

```ts
export type SessionFollowFrame = { readonly type: 'snapshot'; readonly header: SessionWireHeader; readonly cursor: number; readonly records: readonly SessionHistoryRecord[]; readonly hasMore: boolean; readonly projections: SessionProjectionBaseline; readonly assistantStream?: SessionAssistantStreamBaseline; } | SessionEventEntry | { readonly type: 'assistant-stream'; readonly frame: SessionAssistantStreamFrame; };
```

```ts
export type SessionAssistantStreamFrame = { readonly type: 'start'; readonly attemptId: LlmAttemptId; readonly revision: number; readonly startedAfterSeq: SessionSeqCursor; readonly turn: number; readonly step: number; } | { readonly type: 'chunk'; readonly attemptId: LlmAttemptId; readonly revision: number; readonly index: number; readonly time: number; readonly chunk: JsonValue; } | { readonly type: 'end'; readonly attemptId: LlmAttemptId; readonly revision: number; readonly index: number; readonly outcome: { readonly kind: 'committed'; readonly eventType: 'assistant/message' | 'assistant/attempt'; readonly seq: number; } | { readonly kind: 'abandoned'; }; };
```

Request (`SessionFollowRequest`, JSON Schema from the descriptor codec):

```json
{"type":"object","properties":{
  "address":{"anyOf":[
     {"type":"object","properties":{"kind":{"const":"session"},"sessionId":{"type":"string"}},"required":["kind","sessionId"]},
     {"type":"object","properties":{"kind":{"const":"subagent"},"parentSessionId":{},"childSessionId":{},"mode":{"anyOf":[{"const":"one-shot"},{"const":"continuable"}]}},"required":["kind","parentSessionId","childSessionId","mode"]}]},
  "maxMessages":{"type":"number"},
  "assistantStream":{"type":"boolean","const":true}},
 "required":["address"]}
```

**The durable per-event vocabulary** is `SessionEventMap`, declared verbatim in
`$PKG/dsh-api-session-controller/lib/typert.host.js`:

```ts
export interface SessionEventMap {
    'turn/start': { turn: number; };
    'turn/end': { turn: number; reason: TurnEndReason; };
    'step/start': { turn: number; step: number; };
    'step/end': { turn: number; step: number; };
    'user/message': UserMessage;
    'system/message': { turn: number; step: number; message: SystemMessage; };
    'assistant/message': { turn: number; step: number; message: AssistantMessage; stream: AssistantStreamRecord[]; usage?: TokenUsage; interrupted?: true; };
    'assistant/attempt': { turn: number; step: number; stream: AssistantStreamRecord[]; };
    'tool/call': { turn: number; step: number; callId: ToolCallId; name: string; arguments: string; };
    'tool/result': { turn: number; step: number; message: ToolResultMessage; error?: { name: string; code: string; }; meta?: JsonValue; };
    'request/header': { header: EpochHeader; reason: RequestHeaderReason; startsSeries?: true; };
    'request/context': RequestContext;
    'session/end-seed': { inherited?: true; };
    'agent/inbox/spliced': { target: InboxTarget; start: number; removedCount?: number; inserted: UserMessage[]; outcome?: 'canceled'; };
    'approval/asked': { id: ApprovalRequestId; toolName: string; callId?: ToolCallId; reason?: string; };
    'approval/decided': { id: ApprovalRequestId; outcome: ApprovalOutcome; };
    'approval/policy': { policy: ApprovalPolicy; source?: 'delegation'; };
    'tool/ptc-dispatch-start': PtcDispatchStartEventData;
    'tool/ptc-dispatch': PtcDispatchEventData;
    'agent-preset/selected': { agentPreset: string; };
    'command/run': { commandId: CommandId; name: string; args?: string; source: CommandSource; };
    'command/done': { commandId: CommandId; kind: 'success' | 'error'; text?: string; sourceEventSeq?: import('@deepseek-ai/dsh-session/types').SessionSeq; };
    'session/title': SessionTitleEventData;
    'todo/write': { todos: TodoItem[]; };
    'model/selection': ModelSelection;
    'subagent/descriptor': SubagentDescriptorData;
    'sandbox/mode': { mode: SandboxMode; source?: 'delegation'; };
    'subagent/catalog': SubagentCatalogEvent;
    'feedback/record': FeedbackRecord;
    ... (team/*, goal/change, feedback/message-*, compaction/*) ...
}
```

Each arrives wrapped as `{"type":"event","event":{"type":"turn/end","seq":N,"time":T,"data":{...}}}`.

**So: `turn/end` IS a real wire event type — but only as the `event.type` inside a
`session/follow` frame, not as a top-level channel name.**

### 4b. `$events` — the forwarded Cordis-event firehose (WS mux)

`$PKG/dsh-api-remotes/lib/types/remote-events.d.ts` is the **complete allowlist** —
19 host events forwarded to the browser, each with a dispatch mode:

```ts
export declare const API_REMOTE_FORWARDED_EVENTS: readonly [
  { readonly event: "agent-preset/selected"; readonly mode: "emit"; },
  { readonly event: "approval/request"; readonly mode: "waterfall"; },
  { readonly event: "api-session/activity"; readonly mode: "emit"; },
  { readonly event: "api-session/added"; readonly mode: "emit"; },
  { readonly event: "api-session/error"; readonly mode: "emit"; },
  { readonly event: "api-session/removed"; readonly mode: "emit"; },
  { readonly event: "api-session/status"; readonly mode: "emit"; },
  { readonly event: "commands/change"; readonly mode: "emit"; },
  { readonly event: "credentials/reference-updated"; readonly mode: "emit"; },
  { readonly event: "goal/activation-changed"; readonly mode: "emit"; },
  { readonly event: "cordis/request-run"; readonly mode: "emit"; },
  { readonly event: "cordis/request-run-resolved"; readonly mode: "emit"; },
  { readonly event: "cordis/dynamic-package"; readonly mode: "emit"; },
  { readonly event: "cordis/dynamic-retract"; readonly mode: "emit"; },
  { readonly event: "cordis/inspect-query"; readonly mode: "emit"; },
  { readonly event: "cordis/inspect-query-resolved"; readonly mode: "emit"; },
  { readonly event: "llm/adapters-updated"; readonly mode: "emit"; },
  { readonly event: "settings/document-updated"; readonly mode: "emit"; },
  { readonly event: "user-questions/request"; readonly mode: "waterfall"; }
];
```

Wire frames (`$PKG/dsh-api-gateway/lib/types/stream-protocol.d.ts`):

```ts
export interface RemoteEventReadyFrame {
    readonly type: 'ready';
    readonly clientId: RemoteEventClientId;
    readonly host: RemoteEventHostInfo;      // { home: string }
}
export interface RemoteEventEmitFrame {
    readonly type: 'emit';
    readonly event: string;
    readonly args: readonly unknown[];
}
export interface RemoteEventInvocationFrame {
    readonly type: 'waterfall';
    readonly event: string;
    readonly eventId: RemoteEventId;
    readonly agentId: RemoteEventAgentId;
    readonly request: Readonly<Record<string, unknown>>;
}
export interface RemoteEventCancellationFrame {
    readonly type: 'cancel';
    readonly eventId: RemoteEventId;
}
```

Host emits `api-session/status` at
`$PKG/dsh-api-session-controller/lib/index.js:2756`:
`ctx.emit("api-session/status", agent.id, status === "running");`

**`approval/request` is the tool-approval channel**, mode `waterfall`. It arrives as a
`{type:'waterfall'}` frame; the browser MUST answer via **HTTP POST `/api/$events/result`**
(`$PKG/dsh-api-gateway/lib/client.js:673-679`):

```js
const result = { clientId, eventId: frame.eventId, outcome: ... };
const response = await this.connection.rpc.call("/api", REMOTE_EVENT_RESULT_ENDPOINT, { args: result }, signal);
```

`outcome` is one of `{kind:'next'}` | `{kind:'result', value?}` | `{kind:'rejected', error:{name,message,code?,details?}}`
(`$PKG/dsh-api-gateway/lib/types/stream-protocol.d.ts`, `RemoteEventResult`).

An unanswered waterfall **blocks the host turn** until it resolves, is cancelled, or
its Agent context dies (`$PKG/dsh-api-gateway/lib/index.js:684-691`).

### 4c. NEGATIVE RESULTS for the event names in the prompt

* **`assistant/chunk` does NOT exist anywhere in the browser transport.** Grep across
  `dsh-client-connection`, `dsh-api-gateway`, `dsh-api-remotes`,
  `dsh-api-session-controller`, `dsh-cordis-client-runner`: **zero matches**. It survives
  only in **session-format migration** code, e.g.
  `$PKG/dsh-session-format-v1-to-v2/lib/index.js:6`:
  `filter(([type]) => type !== "assistant/chunk" && type !== "assistant/message" ...)`
  and `$PKG/dsh-session-format-v0-to-v1/lib/index.js:37: "assistant/chunk": disposition([`.
  Live assistant tokens arrive **only** as `session/follow` `{"type":"assistant-stream","frame":{"type":"chunk",...}}`
  frames, or embedded in `assistant/message` events.
* **`session/event` is host-internal only** — a Cordis `ctx.on("session/event", (session, event) => ...)`
  event (e.g. `$PKG/dsh-agent-loop/lib/index.js:321`, `$PKG/dsh-acp/lib/index.js:1102`).
  **Zero matches** in the transport packages. It is not in the forwarded allowlist, so it
  never reaches a client.
* There is **no `session/send`** endpoint. Sending a user message is `session/prompt`.

---

## 5. WEBSOCKET vs HTTP

**Both, with a clean split.**

* Unary RPC → **HTTP POST** to `/api/<endpoint>`. `$PKG/dsh-client-connection/lib/client.js:6206-6211`.
* Streaming RPC + the forwarded-event firehose → **one WebSocket** at **`/api/remote.mux`**.
* SSE exists in the tree but belongs to HMR only: `$PKG/dsh-client-hmr/lib/index.js:5`
  `const EVENTS_ENDPOINT = "/plugins/events";` and `$PKG/dsh-client-hmr/lib/client.js:98`
  `const source = new EventSource(EVENTS_ENDPOINT);`. The only `text/event-stream` string in
  the host server is a compression exclusion, `$PKG/dsh-host-webserver/lib/index.js:113`.

**Upgrade mounting** — `$PKG/dsh-api-gateway/lib/index.js:457-477`:

```js
ctx.inject(["connection", "webServer"], (webCtx) => {
    const mux = new RemoteStreamMuxServer((endpoint, payload, signal) => this.openWireStream(endpoint, payload, signal), this.wireStream.failure, resolved.websocketHeartbeatIntervalMs);
    webCtx.effect(() => {
        const route = {
            path: REMOTE_STREAM_MUX_PATH,
            handler: (req, socket, head) => {
                const rejection = webCtx.connection.requestRejection(req);
                if (rejection !== void 0) {
                    rejectRemoteStreamUpgrade(socket, rejection);
                    return;
                }
                mux.handleUpgrade(req, socket, head);
            }
        };
        const unregister = webCtx.webServer.registerUpgrade(route);
        ...
```

* The upgrade path is matched **exactly** — `$PKG/dsh-host-webserver/lib/index.js:273`:
  `route = this.upgrades.get(new URL(req.url ?? "/", "http://x").pathname);` (no prefix matching).
* Viewport URL construction — `$PKG/dsh-api-gateway/lib/client.js:541-547`:
  `const url = new URL(REMOTE_STREAM_MUX_PATH, base); url.protocol = url.protocol === "https:" ? "wss:" : "ws:";`
* Heartbeat: WebSocket **Ping** control frames every `websocketHeartbeatIntervalMs`
  (default 2000), terminate after `MAX_MISSED_HEARTBEATS`
  (`$PKG/dsh-api-gateway/lib/index.js:251-268`, `:398`: `const DEFAULT_WEBSOCKET_HEARTBEAT_INTERVAL_MS = 2e3;`).
  **An Android client must answer WS Pings with Pongs** or be terminated.
* Rejection is a raw HTTP response written to the socket before upgrade —
  `$PKG/dsh-api-gateway/lib/index.js:377-388` (`HTTP/1.1 401 Unauthorized` / `403 Forbidden`).

**`ws` library:** `new WebSocketServer({ noServer: true })` (`$PKG/dsh-api-gateway/lib/index.js:203`).

### `dsh-api-remotes` — read in full, it is tiny

Its **host** half (`$PKG/dsh-api-remotes/lib/index.js`, 5.7 KB) does only this
(`$PKG/dsh-api-remotes/lib/index.js:110-113`):

```js
const inject = ["typertGateway"];
function apply(ctx) {
	ctx.effect(() => ctx.typertGateway.registerRemoteEvents(remoteEventSource(ctx), { home: homedir() }), "api-remotes: forwarded Cordis event source");
}
```

It builds one `RemoteEventQueue` bridging 19 synchronous Cordis listeners into an
`AsyncIterable` for the gateway. **It registers no routes, no channels, no endpoints.**
Its browser half is a type-only re-export facade (`$PKG/dsh-api-remotes/lib/types/client/index.d.ts`).
So `dsh-api-remotes` is **not** the transport — the transport is
`dsh-client-connection` (HTTP `/api`) + `dsh-api-gateway` (WS mux + Typert dispatch).

---

## 6. AUTH ON THE WIRE

Two sequential gates, both applied by `HostConnectionService.requestRejection`
(`$PKG/dsh-client-connection/lib/index.js:552-556`):

```js
/** Apply the configured Host/Origin fence, then browser authentication. */
requestRejection(request) {
    if (!isTrustedApiRequest(request, this.trustedHosts)) return 403;
    return this.browserAuth.isAuthenticated(request) ? void 0 : 401;
}
```

### (a) Host/Origin trust fence — `isTrustedApiRequest`

`$PKG/dsh-client-connection/lib/index.js:201-215`:

```js
function isTrustedApiRequest(request, trustedHosts) {
	const host = header$1(request.headers, "host");
	if (host === void 0) return false;
	const hostUrl = parseAuthority(host);
	if (hostUrl === void 0) return false;
	if (!isLoopbackHostname(hostUrl.hostname) && !isTrustedAuthority(hostUrl, trustedHosts)) return false;
	if (header$1(request.headers, "sec-fetch-site") === "cross-site") return false;
	const origin = header$1(request.headers, "origin");
	if (origin === void 0) return true;
	try {
		return new URL(origin).host === hostUrl.host;
	} catch {
		return false;
	}
}
```

Answers:

* **`Host` is MANDATORY.** Missing → 403. It must parse as an authority.
* **`Host` must be loopback or explicitly trusted.** Loopback means, per
  `$PKG/dsh-client-connection/lib/index.js:117-121`: `"localhost"`, `"[::1]"`, or any
  IPv4 in `127/8`. Otherwise it must match a `trustedHosts` entry — exact `host:port`,
  or a port-less `host` matching any port (`:188-194`).
* **`Origin` MAY be omitted** — `if (origin === void 0) return true;`. If present, its
  `.host` must equal the `Host` authority exactly.
* **`sec-fetch-site: cross-site` is an instant 403.** Omit it (non-browser clients do).
* This fence is **not authentication** — its own doc says so:
  `$PKG/dsh-client-connection/lib/index.js:136`: *"this fence is not an auth layer."*

### (b) `BrowserAuth.isAuthenticated` — cookie only

`$PKG/dsh-client-connection/lib/index.js:431-441`:

```js
isAuthenticated(request) {
    const authority = requestAuthority(request.headers);
    const rawCookie = header(request.headers, "cookie");
    if (authority === void 0 || rawCookie === void 0) return false;
    const value = cookieValue(rawCookie, cookieName(authority));
    if (value === void 0) return false;
    const payload = decodeCookie(value, this.secret);
    if (payload === void 0 || payload.authority !== authority) return false;
    const now = Date.now();
    return payload.issuedAt <= now && payload.expiresAt > now && payload.expiresAt > payload.issuedAt && payload.expiresAt - payload.issuedAt <= this.maxAgeMilliseconds;
}
```

**There is NO Bearer token, NO API key, NO query-param auth on `/api`.** The only
credential is the signed **cookie**, and it is **authority-bound**.

**Cookie name derivation from authority** — `$PKG/dsh-client-connection/lib/index.js:252-261` and `:280-282`:

```js
/** Canonical request authority used as the cookie name and signed audience. */
function requestAuthority(headers) {
	const host = header(headers, "host");
	if (host === void 0) return void 0;
	try {
		return new URL(`http://${host}`).host;
	} catch {
		return;
	}
}
```
```js
function cookieName(authority) {
	return COOKIE_PREFIX + encodeBase64Url(createHash("sha256").update(authority).digest());
}
```

with `$PKG/dsh-client-connection/lib/index.js:223-224`:

```js
const COOKIE_PREFIX = "dsh-auth-";
```

So the cookie is named **`dsh-auth-<base64url(sha256(URL("http://"+Host).host))>`** — i.e.
`dsh-auth-` + unpadded base64url of the SHA-256 **of the exact `Host` header value**
(WHATWG-normalized: hostname lowercased, default port stripped, IPv6 bracketed).

Cookie value format (`$PKG/dsh-client-connection/lib/index.js:298-301`):

```js
function encodeCookie(payload, secret) {
	const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), "utf8"));
	return `v1.${body}.${encodeBase64Url(signature(secret, body))}`;
}
```

Cookie attributes (`$PKG/dsh-client-connection/lib/index.js:292-294`):

```js
function sessionCookie(name, value, expiresAt, maxAgeSeconds) {
	return `${name}=${value}; Max-Age=${String(maxAgeSeconds)}; Path=/; Expires=${new Date(expiresAt).toUTCString()}; HttpOnly; SameSite=Strict`;
}
```

**`SameSite=Strict` + `HttpOnly`** — a plain Android HTTP client just needs to store and
replay the `Set-Cookie` value verbatim; it is not in a browser origin, so `SameSite` does
not restrict it.

Signing secret: HMAC-SHA256 with a 32-byte secret persisted once per Harness home at
credential key `credentialKey("client-connection", "browser-session")`
(`$PKG/dsh-client-connection/lib/index.js:219`, `:321-338`). Default cookie lifetime 30 days
(`cookieMaxAgeDays` default 30, `$PKG/dsh-client-connection/lib/index.js:740`).

### (c) How a client obtains the cookie — the launch-token exchange

`$PKG/dsh-client-connection/lib/index.js:386-409` (`BrowserAuth.authorizeIndex`):

```js
authorizeIndex(req, res) {
    const url = new URL(req.url ?? "/", "http://dsh.invalid");
    const tokens = url.searchParams.getAll(TOKEN_QUERY);
    if (tokens.length > 0) {
        const authority = requestAuthority(req.headers);
        if (req.method === "GET" && url.pathname === "/" && tokens.length === 1 && authority !== void 0 && tokenMatches(tokens.join(""), this.launchToken)) {
            const issuedAt = Date.now();
            const expiresAt = issuedAt + this.maxAgeMilliseconds;
            const value = encodeCookie({ version: COOKIE_PAYLOAD_VERSION, authority, issuedAt, expiresAt }, this.secret);
            res.writeHead(303, {
                "cache-control": "no-store",
                "location": "/",
                "referrer-policy": "no-referrer",
                "set-cookie": sessionCookie(cookieName(authority), value, expiresAt, Math.floor(this.maxAgeMilliseconds / 1e3))
            });
            res.end();
            return false;
        }
        ...
```

with `TOKEN_QUERY = "token"` (`:222`).

**Requirements: `GET /?token=<launchToken>` — method GET, pathname exactly `/`,
exactly one `token` parameter, and a parsable `Host`.** Response: `303` +
`Set-Cookie: dsh-auth-...`.

The token is a per-process random 32-byte base64url value
(`$PKG/dsh-client-connection/lib/index.js:240-246`):

```js
function processLaunchToken(owner) {
	const existing = PROCESS_LAUNCH_TOKENS.get(owner);
	if (existing !== void 0) return existing;
	const created = encodeBase64Url(randomBytes(SECRET_BYTES));
	PROCESS_LAUNCH_TOKENS.set(owner, created);
	return created;
}
```

It is used **only** for the index exchange — `authorizeIndex` is invoked by
`dsh-host-frontend-static` **for index responses only**
(`$PKG/dsh-host-frontend-static/lib/index.js:59-62`):

```js
if (target === distRoot || target === distIndex) {
    if (!authorizeIndex()) return;
    body = await renderIndex();
    type = HTML_MIME;
}
```

and the fallback passes it as `() => ctx.connection.authorizeIndex(req, res)`
(`$PKG/dsh-host-frontend-static/lib/index.js:95`). **It is printed to the console** —
`$PKG/dsh-web-app/lib/index.js:198-203`:

```js
const authenticatedUrl = connectionCtx.connection.authenticatedUrl(webUrl);
...
if (config.printUrl) console.log(`dsh web: ${authenticatedUrl}${lanUrl === void 0 ? "" : ` (LAN: ${lanUrl})`}`);
```

So the token is **out-of-band only** (console / the URL `dsh web` prints). There is no
HTTP endpoint that returns it.

### (d) Critical: reachability and the LAN problem

* The dsh web server binds **`127.0.0.1` by default** —
  `$PKG/dsh-web-app/cordis.patch.yml`:
  `host: !!js ctx.webStartup.host ?? '127.0.0.1'`, `port: !!js ctx.webStartup.port ?? 3080`.
* The webserver's host config accepts **only two literals** —
  `$PKG/dsh-host-webserver/lib/index.js:141`:
  `host: z.union([z.const("127.0.0.1"), z.const("0.0.0.0")]).required(),`.
* **`--host 0.0.0.0` is deliberately rejected by the CLI** —
  `$PKG/dsh-web-app/lib/startup.js`:
  ```js
  if (options.host === "0.0.0.0") program.error("error: --host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network; use 127.0.0.1 instead");
  ```
* If a profile patch does set `0.0.0.0`, LAN literals are auto-trusted —
  `$PKG/dsh-web-app/lib/index.js:83-89`:
  ```js
  function resolveLanTrust(bindHost, extra) {
      const lanAddresses = bindHost === ALL_INTERFACES_HOST ? Object.values(networkInterfaces()).flat().filter((iface) => iface !== void 0 && iface.family === "IPv4" && !iface.internal).map((iface) => iface.address) : [];
      return { lanAddresses, trustedHosts: [...lanAddresses, ...extra] };
  }
  ```
  and those are wired into Connection — `$PKG/dsh-web-app/cordis.patch.yml`:
  ```yaml
  - id: connection
    name: '@deepseek-ai/dsh-client-connection'
    inject: [webRuntime]
    config:
      trustedHosts: !!js ctx.webRuntime.trustedHosts
  ```

This composition was **confirmed empirically** by capturing the real CLI's composed tree
(`dsh --profile web --dump-config`) at
`/root/project/remote-use/.research/dump-real-web.yml`, which contains the rows verbatim:

```yaml
- id: webserver
  name: '@deepseek-ai/dsh-host-webserver'
  inject:
    - webStartup
  config:
    host: !!js ctx.webStartup.host ?? '127.0.0.1'
    port: !!js ctx.webStartup.port ?? 3080
```
```yaml
- id: connection
  name: '@deepseek-ai/dsh-client-connection'
  inject:
    - webRuntime
  config:
    trustedHosts: !!js ctx.webRuntime.trustedHosts
```

**Consequence for a reverse-proxy gateway (your design):** the gateway MUST rewrite the
upstream `Host` to a loopback authority (e.g. `127.0.0.1:3080`) — forwarding the
Android-supplied `Host: 192.168.x.y:port` would 403. And because the cookie is
authority-bound, the cookie must be minted for that **same** `127.0.0.1:3080` authority
and the upstream `Cookie` header must carry the `dsh-auth-<sha256("127.0.0.1:3080")>` name.

---

## 7. MINIMUM VIABLE ANDROID SEQUENCE

**Read this first: the transport is NOT plain REST.** It is:

* a **JSON-RPC-over-HTTP-POST** unary channel at `/api/<namespace>/<method>`, **plus**
* **one WebSocket** at `/api/remote.mux` for all streaming and for the event firehose.

An Android client that only speaks REST will be able to create sessions, send prompts and
list sessions, but **will receive no streamed assistant output**. Streaming requires a real
WebSocket client.

Also note the deployment constraint from §6(d): a stock `dsh web` binds `127.0.0.1:3080`
and the CLI refuses `--host 0.0.0.0`. **The phone cannot reach the daemon directly.** Your
auth-gateway must terminate the Android connection and speak loopback to dsh.

Below, `$GW` is the gateway origin the phone talks to, and `$UP` is the upstream dsh
authority (`127.0.0.1:3080`).

### Step 0 — obtain the launch token (out of band, once per dsh process)

The token is per-process random and printed only to dsh's console
(`$PKG/dsh-web-app/lib/index.js:203`):
`dsh web: http://127.0.0.1:3080/?token=<LAUNCH_TOKEN>`

There is **no HTTP API that returns it**. The gateway must capture it from dsh's stdout at
startup (or accept it from the operator once). Cache the resulting cookie; do not re-mint
per request.

### Step 1 — authenticate: exchange the token for the cookie

```
GET /?token=<LAUNCH_TOKEN> HTTP/1.1
Host: 127.0.0.1:3080
```
*(the gateway issues this to `$UP`; the phone never sees it)*

Expected response (`$PKG/dsh-client-connection/lib/index.js:401-407`):

```
HTTP/1.1 303 See Other
location: /
cache-control: no-store
referrer-policy: no-referrer
set-cookie: dsh-auth-<b64url(sha256("127.0.0.1:3080"))>=v1.<b64url(json)>.<b64url(hmac)>; Max-Age=2592000; Path=/; Expires=...; HttpOnly; SameSite=Strict
```

Store the cookie **value** (the whole `name=value` pair). It is bound to the authority
string `127.0.0.1:3080` — reusing it under a different `Host` fails
(`payload.authority !== authority` → false, `$PKG/dsh-client-connection/lib/index.js:438`).

Failure mode: any other outcome, or a `401` body
`dsh web authentication required; reopen the URL printed by dsh web.`
(`$PKG/dsh-client-connection/lib/index.js:447`).

### Step 2 — every subsequent `/api` request

Mandatory headers for **every** `/api` call (`$PKG/dsh-client-connection/lib/index.js:6194-6211`,
`:201-215`):

```
Host: 127.0.0.1:3080
Cookie: dsh-auth-<sha256-b64url>=v1.<body>.<sig>
Content-Type: application/json
```
Do **not** send `Origin` (or, if you must, set it to exactly `http://127.0.0.1:3080`).
Never send `Sec-Fetch-Site: cross-site`.

### Step 3 — list sessions

```
POST /api/session/list HTTP/1.1
Host: 127.0.0.1:3080
Cookie: dsh-auth-...=v1...
Content-Type: application/json

{"type":"client-request","rpcId":"<uuid-v4>","method":"session/list","payload":{"args":{"_request":{}}}}
```

`_request` is `{"cursor"?: string}` (optional). Result:

```json
{"type":"server-response","rpcId":"<same uuid>","result":{"ok":true,"value":{"items":[
  {"sessionId":"...","updatedAt":1750000000000,"running":false,"blank":false,
   "parentSessionId":"...","origin":"subagent","cwd":"/path","projections":{...}}]}}}
```

Note the wire field name is `_request` (with the underscore) — from the generated
descriptor in `$PKG/dsh-api-session-controller/lib/typert.host.js`.

### Step 4 — create a session (optional; you can also reuse an existing id)

```
POST /api/session/create HTTP/1.1
Content-Type: application/json

{"type":"client-request","rpcId":"<uuid>","method":"session/create","payload":{"args":{"request":{"cwd":"/root/project/remote-use"}}}}
```

→ `{"result":{"ok":true,"value":{"sessionId":"<sid>"}}}`
(`SessionCreateRequest` = `{workspaceId?, cwd?, sessionId?, agentPreset?}`, all optional;
only `sessionId` is required in the result.)

### Step 5 — open the live stream BEFORE sending the prompt

Open the WebSocket **first**, so no early frames are missed:

```
GET /api/remote.mux HTTP/1.1
Host: 127.0.0.1:3080
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: <random>
Sec-WebSocket-Version: 13
Cookie: dsh-auth-...=v1...
Origin: http://127.0.0.1:3080
```

Then send one text frame to open the logical stream:

```json
{"type":"open","streamId":"s1","endpoint":"session/follow","payload":{"args":{"request":{"address":{"kind":"session","sessionId":"<sid>"},"assistantStream":true}}}}
```

The host replies with text frames on the same socket:

```json
{"type":"item","streamId":"s1","value":{"type":"snapshot","header":{...},"cursor":N,"records":[...],"hasMore":false,"projections":{...}}}
{"type":"item","streamId":"s1","value":{"type":"event","event":{"type":"turn/start","seq":N,"time":T,"data":{"turn":0}}}}
{"type":"item","streamId":"s1","value":{"type":"assistant-stream","frame":{"type":"start","attemptId":"...","revision":1,"startedAfterSeq":N,"turn":0,"step":0}}}
{"type":"item","streamId":"s1","value":{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"...","revision":1,"index":0,"time":T,"chunk":{...}}}}
{"type":"item","streamId":"s1","value":{"type":"assistant-stream","frame":{"type":"end","attemptId":"...","revision":1,"index":0,"outcome":{"kind":"committed","eventType":"assistant/message","seq":N}}}}
{"type":"item","streamId":"s1","value":{"type":"event","event":{"type":"turn/end","seq":N,"time":T,"data":{"turn":0,"reason":"..."}}}}
```

To stop: `{"type":"cancel","streamId":"s1"}`.
Terminal frames: `{"type":"end","streamId":"s1"}` or `{"type":"error","streamId":"s1","error":{"code":"...","message":"...","details":{}}}`.

**You must also open `$events`** on the same socket if you want tool-approval prompts:

```json
{"type":"open","streamId":"ev","endpoint":"$events","payload":{"args":{}}}
```
→ `{"type":"item","streamId":"ev","value":{"type":"ready","clientId":"<cid>","host":{"home":"/root"}}}`
→ later `{"type":"item","streamId":"ev","value":{"type":"waterfall","event":"approval/request","eventId":"<eid>","agentId":"<aid>","request":{...}}}`

and you **must answer it over HTTP**, not the socket — the host turn blocks until then:

```
POST /api/$events/result
{"type":"client-request","rpcId":"<uuid>","method":"$events/result","payload":{"args":{"clientId":"<cid>","eventId":"<eid>","outcome":{"kind":"next"}}}}
```

**Answer every WebSocket Ping with a Pong** (2000 ms default heartbeat) or the host terminates the socket.

### Step 6 — send the user message

```
POST /api/session/prompt HTTP/1.1
Host: 127.0.0.1:3080
Cookie: dsh-auth-...=v1...
Content-Type: application/json

{"type":"client-request","rpcId":"<uuid>","method":"session/prompt","payload":{"args":{"request":{
  "requestId":"<uuid>",
  "sessionId":"<sid>",
  "mode":"queue",
  "content":[{"type":"text","text":"hello"}],
  "clientTimeZone":"Asia/Shanghai"
}}}}
```

Exact `SessionPromptRequest` schema (`$PKG/dsh-api-session-controller/lib/typert.host.js`):

```json
{"type":"object","properties":{
  "requestId":{"type":"string"},
  "sessionId":{"type":"string"},
  "mode":{"anyOf":[{"const":"queue"},{"const":"steer"}]},
  "content":{"type":"array","items":{"anyOf":[
      {"type":"object","properties":{"type":{"const":"text"},"text":{"type":"string"}},"required":["type","text"]},
      {"type":"object","properties":{"type":{"const":"image"},"mediaType":{"anyOf":[{"const":"image/png"},{"const":"image/jpeg"},{"const":"image/webp"},{"const":"image/gif"}]},"data":{"type":"string"},"name":{"type":"string"}},"required":["type","mediaType","data"]},
      {"type":"object","properties":{"type":{"const":"file"},"receiptId":{"type":"string"}},"required":["type","receiptId"]}]}},
  "clientTimeZone":{"type":"string"}},
 "required":["requestId","sessionId","mode","content"]}
```

→ `{"type":"server-response","rpcId":"<uuid>","result":{"ok":true,"value":{"accepted":true}}}`
(`accepted` is `z.literal(true)` — the endpoint is fire-and-confirm, not a turn result.)

### Step 7 — cancel a turn

```
POST /api/session/cancel
{"type":"client-request","rpcId":"<uuid>","method":"session/cancel","payload":{"args":{"request":{"sessionId":"<sid>"}}}}
```
→ `{"result":{"ok":true,"value":{"accepted":true}}}`

### Summary of the minimum viable path

| # | step | transport | path |
|---|---|---|---|
| 0 | capture launch token | out of band (stdout) | — |
| 1 | mint cookie | HTTP GET | `/?token=...` |
| 2 | list sessions | HTTP POST | `/api/session/list` |
| 3 | (create session) | HTTP POST | `/api/session/create` |
| 4 | open live stream | **WebSocket** | `/api/remote.mux` → `session/follow` |
| 5 | (open approvals) | **WebSocket** | `/api/remote.mux` → `$events` |
| 6 | send message | HTTP POST | `/api/session/prompt` |
| 7 | (cancel) | HTTP POST | `/api/session/cancel` |
| 8 | (answer approval) | HTTP POST | `/api/$events/result` |

---

## Evidence artifacts (in this workspace)

| file | what it is |
|---|---|
| `dsh-transport-spec.md` | this report |
| `_typert_dump.json` | raw machine-extracted descriptor dump: all **84** invocations from the 16 generated `lib/typert.host.js` manifests, with `namespace`, `method`, `mode` (stream/unary), `scope`, `cancellation`, per-parameter `wire`/`source`/`lookup`/`typeSymbol` and full JSON-Schema of every arg and result codec |
| `_endpoint_table.md` | the same 84 endpoints rendered as a compact markdown table |
| `.research/dump-real-web.yml` | real `dsh --profile web --dump-config` output (562 lines), the composed profile tree used to confirm host/port/`trustedHosts` wiring empirically |
| `.research/services.txt` | empirically enumerated Cordis service registry (135 rows, 122 unique names) from a booted dsh — confirms the service keys used throughout this report |

**Empirical service-name cross-check.** A live dsh boot enumerated the service registry and
independently confirms the exact keys cited above
(`.research/services.txt`):

```
connection      dsh-client-connection   dsh-client-connection/lib/types/rpc-host.d.ts
typertGateway   dsh-api-gateway         dsh-api-gateway/lib/types/types.d.ts
typert          dsh-typert-protocol     dsh-typert-protocol/lib/types/types.d.ts
webServer       dsh-host-webserver      dsh-host-webserver/lib/types/index.d.ts
remote          dsh-api-gateway         dsh-api-gateway/lib/types/client/index.d.ts   (browser-side)
clientModules   dsh-client-modules      dsh-client-modules/lib/types/index.d.ts
credentials     dsh-credentials         dsh-credentials/lib/types/index.d.ts
```

Extraction method for the endpoint set (no inference): a Node ESM script imported every
`$PKG/*/lib/typert.host.js`, read `TYPERT.invocations`, and converted each zod codec with
`z.toJSONSchema`. Reproduce with:

```bash
cd /root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh && node --input-type=module -e '
const fs=await import("node:fs"),path=await import("node:path");
const R="/root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai";
for (const p of fs.readdirSync(R)) { const f=path.join(R,p,"lib","typert.host.js"); if(!fs.existsSync(f))continue;
  const T=(await import(f)).TYPERT; for(const i of (T.invocations??[]))
    console.log(p, i.namespace+"/"+i.method, i.mode??"unary"); }'
```

---

## PROMPT CLAIMS DEBUNKED

| # | Claim in the prompt | Verdict | Evidence |
|---|---|---|---|
| 1 | "the WebSocket endpoint is `/api/remote.mux`" | **TRUE** | `$PKG/dsh-api-gateway/lib/types/stream-protocol.d.ts`: `export declare const REMOTE_STREAM_MUX_PATH = "/api/remote.mux";` — it is a **WebSocket upgrade** route, registered via `webServer.registerUpgrade` (`$PKG/dsh-api-gateway/lib/index.js:471`), matched exactly (`$PKG/dsh-host-webserver/lib/index.js:273`). |
| 2 | "there are **47 REST + SSE routes**" | **FALSE** | Static enumeration gives **8** web-server HTTP route registrations (7 named + 1 fallback), **1** WebSocket upgrade route, and **5** exact fetch sub-routes dispatched inside the single `/api` prefix mount. The dynamic JSON-RPC surface is **84** Typert endpoints across 15 packages (extracted from the 16 generated `lib/typert.host.js` manifests). **Neither number is 47.** SSE exists in the tree but only for dev HMR at `/plugins/events` (`$PKG/dsh-client-hmr/lib/index.js:5`) — it carries **no** API traffic. |
| 3 | "the transport is REST + SSE" (implied) | **FALSE** | The transport is **HTTP POST JSON-RPC** (`/api/<ns>/<method>`) **+ one WebSocket** (`/api/remote.mux`). The server never sets `text/event-stream` for API traffic — the only occurrence in `dsh-host-webserver` is a compression exclusion (`$PKG/dsh-host-webserver/lib/index.js:113`). |
| 4 | "`ctx.workspaceFiles` has a 'remote namespace'" | **PARTLY TRUE** | The remote namespace **is** `workspaceFiles` (7 endpoints: `workspaceFiles/list`, `/read`, `/readAll`, `/readBytes`, `/readRelated`, `/stat`, `/changes`). Namespace defaults to the service key — `$PKG/dsh-api-workspace-files/lib/index.js:369`: `super(ctx, "workspaceFiles");`. But the **client** accessor is `ctx.remote.workspaceFiles`, e.g. `$PKG/dsh-api-workspace-files/lib/client.js:355`: `const stat = () => remote.workspaceFiles.stat(sessionId, path, signal);`. The claim's phrasing conflates the host service `ctx.workspaceFiles` with the remote namespace. |
| 5 | "live agent events arrive as `assistant/chunk`" | **FALSE** | The literal `assistant/chunk` has **zero** matches in `dsh-client-connection`, `dsh-api-gateway`, `dsh-api-remotes`, `dsh-api-session-controller`, `dsh-cordis-client-runner`. It survives only in session-format migration code (`$PKG/dsh-session-format-v1-to-v2/lib/index.js:412`, `$PKG/dsh-session-format-v0-to-v1/lib/index.js:37`). Live tokens arrive as `session/follow` frames `{"type":"assistant-stream","frame":{"type":"chunk",...}}`. |
| 6 | "`turn/end` is an event channel" | **PARTLY TRUE** | `turn/end` is a real event **type**, but it is the `event.type` **inside** a `session/follow` frame — `'turn/end': { turn: number; reason: TurnEndReason; }` in `SessionEventMap` (`$PKG/dsh-api-session-controller/lib/typert.host.js`). It is **not** a standalone subscription channel. |
| 7 | "`session/event` is a wire subscription" | **FALSE** | `session/event` is a **host-internal Cordis event** (`ctx.on("session/event", (session, event) => ...)`, e.g. `$PKG/dsh-agent-loop/lib/index.js:321`). **Zero** matches in the transport packages, and it is **not** in the forwarded allowlist (`$PKG/dsh-api-remotes/lib/types/remote-events.d.ts`), so it never reaches a client. |
| 8 | "there are many logical channels like `/api`" | **FALSE** | `rpc.handle(` — the API for registering extra channels — is called **zero** times in the entire installed tree. `/api` is additionally **reserved** and cannot be `handle`d (`$PKG/dsh-client-connection/lib/index.js:694`). There is exactly **one** logical channel: `/api`. |
| 9 | "auth can be done with a header/token" (implied by "auth on the wire") | **FALSE for a header scheme** | The only credential is an authority-bound **HMAC-signed cookie**; `BrowserAuth.isAuthenticated` reads only `Host` + `Cookie` (`$PKG/dsh-client-connection/lib/index.js:431-441`). There is **no** Bearer/API-key path. The launch token works **only** for `GET /?token=<t>` and only to mint the cookie (`:392`). |
| 10 | (unstated but load-bearing) "the phone can just connect to the LAN address" | **FALSE by default** | `dsh web` binds `127.0.0.1:3080` (`$PKG/dsh-web-app/cordis.patch.yml`), the webserver host schema allows only `127.0.0.1` or `0.0.0.0` (`$PKG/dsh-host-webserver/lib/index.js:141`), and the CLI **hard-refuses** `--host 0.0.0.0`: *"intentionally not supported yet for safety: it would expose remote code execution to the network"* (`$PKG/dsh-web-app/lib/startup.js`). A gateway or a custom profile patch is mandatory. |

### Additional corrections worth carrying forward

* Streaming is **not** chunked HTTP and **not** SSE — it is WebSocket text frames muxed by `streamId`.
* Business errors are **HTTP 200** with `result.ok === false`; only carrier faults are non-2xx.
* `rpcId` is validated only as `z.string()` on the wire — do not rely on the server rejecting a malformed id.
* Exactly **4** of 84 endpoints are streaming: `session/control`, `session/follow`, `workspace/follow`, `workspaceFiles/changes`.
* Sending a user message is **`session/prompt`** (unary), not `session/send` — that endpoint does not exist.
* `ctx.connection.fetch.register` exact routes bypass the `<ns>/<method>` shape entirely and are matched by full pathname before RPC dispatch.
