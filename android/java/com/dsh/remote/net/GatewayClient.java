package com.dsh.remote.net;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Client for the dsh-remote-gateway HTTP contract (docs/GATEWAY-API.md, frozen v1):
 * /gateway/* authentication routes and /fs/* workspace file routes.
 */
public final class GatewayClient {

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
        http.downloadTo("/fs/download?path=" + Http.urlEncode(path), dest, resume);
        return dest.length();
    }

    /** POST /fs/upload (multipart, mode A). Returns the parsed body. */
    public Object upload(String relPath, File file) throws IOException {
        Http.Response resp = http.uploadFile(relPath, file);
        if (!resp.ok()) throw gatewayError(resp);
        return resp.json();
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