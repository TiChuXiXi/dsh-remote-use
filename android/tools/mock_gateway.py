#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mock_gateway.py — TEST DOUBLE of the dsh-remote-gateway HTTP + WebSocket contract.

##############################################################################
#                             !!! TEST DOUBLE !!!                            #
#                                                                            #
# This is a throwaway-but-careful imitation of the FROZEN gateway contract   #
# (docs/GATEWAY-API.md v1 + dsh-transport-spec.md sections 2/4a/4b/5/7).    #
# It is NOT the real dsh-remote-gateway, it never talks to a real dsh        #
# daemon, and NOTHING it does proves anything about the real gateway or      #
# about real dsh. It exists only to EXECUTE the pure-Java client harness     #
# (android/verify/VerifyMain.java) end-to-end on aarch64 Kylin Linux using   #
# ONLY the python3 standard library (no flask / websockets / uvicorn).       #
#                                                                            #
# Every log line is prefixed with "mock-gateway|TEST DOUBLE" so there is     #
# no chance of mistaking this for the real product.                          #
##############################################################################

Usage:
  python3 tools/mock_gateway.py --port 18081 --user admin --pass devpassword \\
                                --workspace /abs/path [--host 127.0.0.1] \\
                                [--no-approve] [--fail-method ns/method]

Implemented surface (per the frozen docs):
  HTTP
    GET  /gateway/health                     (no auth)
    POST /gateway/login                      (no auth)
    GET  /gateway/session                    (auth)
    POST /gateway/logout                     (auth; revokes the presented token)
    GET  /fs/list?path=<rel>                 (auth)
    GET  /fs/download?path=<rel>             (auth; Range -> 206, malformed -> 416)
    POST /fs/upload                          (auth; multipart mode A, atomic write)
    POST /api/<ns>/<method>                  (auth; JSON-RPC over HTTP, verifies
                                             envelope.method == ns/method, business
                                             failures are HTTP 200 result.ok=false)
  WebSocket (RFC 6455) at GET /api/remote.mux (auth via bearer token)
    server frames are UNMASKED; client frames MUST be masked (unmasked -> 1002),
    binary client frames -> 1003, fragmentation + interleaved control frames
    supported, Ping(b"hb") every 2000 ms on every connection (timeout 6 s -> 1001),
    mux protocol: open/cancel in, item/error/end out, $events ready frame,
    session/follow snapshot + scripted turn incl. approval waterfall.

Exit summary on SIGINT/SIGTERM: connections, handshake failures, pings vs pongs,
unmasked / binary client frames, approvals answered (raw outcome), prompts.
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import signal
import socket
import struct
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --------------------------------------------------------------------------
# Frozen contracts (verbatim from dsh-transport-spec.md)
# --------------------------------------------------------------------------

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MUX_PATH = "/api/remote.mux"
PING_INTERVAL_S = 2.0          # dsh default websocketHeartbeatIntervalMs = 2e3
PONG_TIMEOUT_S = 6.0           # MAX_MISSED_HEARTBEATS behaviour (3 x 2 s)
APPROVAL_TIMEOUT_S = 30.0
CHUNK_GAP_S = 0.04             # ~40 ms gaps so streaming is observably incremental

# The scripted turn's sentence. The harness default prompt asks for exactly the
# token PONG-HARNESS; it prints every chunk live.
CHUNK_WORDS = ["The ", "answer ", "is ", "PONG-HARNESS ", "and ", "streaming ", "works."]
FULL_SENTENCE = "".join(CHUNK_WORDS)
assert "PONG-HARNESS" in FULL_SENTENCE

LOG_LOCK = threading.Lock()
START_MS = int(time.time() * 1000)


def log(fmt, *args):
    """Every mock log line is unmistakably a TEST DOUBLE line."""
    msg = (fmt % args) if args else fmt
    line = "[mock-gateway|TEST DOUBLE] %s %s" % (time.strftime("%H:%M:%S"), msg)
    with LOG_LOCK:
        print(line, flush=True)


def now_ms():
    return int(time.time() * 1000)


# --------------------------------------------------------------------------
# Small thread-safe counter
# --------------------------------------------------------------------------

class Counter(object):
    def __init__(self):
        self._lock = threading.Lock()
        self._v = {}

    def inc(self, name, n=1):
        with self._lock:
            self._v[name] = self._v.get(name, 0) + n

    def get(self, name):
        with self._lock:
            return self._v.get(name, 0)

    def snapshot(self):
        with self._lock:
            return dict(self._v)


# --------------------------------------------------------------------------
# CLI config
# --------------------------------------------------------------------------

class Config(object):
    pass


def parse_args(argv):
    p = argparse.ArgumentParser(description="TEST DOUBLE of the dsh-remote-gateway (NOT the real gateway).")
    p.add_argument("--port", type=int, default=18081)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--user", required=True)
    p.add_argument("--pass", dest="password", required=True)
    p.add_argument("--workspace", required=True, help="absolute workspace root served by /fs/*")
    p.add_argument("--approve", dest="approve", action="store_true", default=True,
                   help="script the approval waterfall on $events (default ON)")
    p.add_argument("--no-approve", dest="approve", action="store_false",
                   help="disable the scripted approval waterfall")
    p.add_argument("--fail-method", default=None, metavar="ns/method",
                   help="TEST DOUBLE switch: force one RPC endpoint to return a business error")
    args = p.parse_args(argv)
    if args.fail_method and "/" not in args.fail_method:
        p.error("--fail-method must look like 'ns/method'")
    cfg = Config()
    cfg.port = args.port
    cfg.host = args.host
    cfg.user = args.user
    cfg.password = args.password
    cfg.workspace = os.path.abspath(args.workspace)
    cfg.approve = args.approve
    cfg.fail_method = args.fail_method
    cfg.token_secret = os.urandom(32)
    cfg.session_max_age_ms = 30 * 24 * 3600 * 1000  # doc default sessionMaxAgeDays=30
    return cfg


# --------------------------------------------------------------------------
# Global state (TEST DOUBLE bookkeeping only)
# --------------------------------------------------------------------------

class State(object):
    def __init__(self):
        self.issued = set()            # bearer tokens this mock minted and did not revoke
        self.issued_lock = threading.Lock()
        self.sessions = {}             # sessionId -> dict(cwd, conn, stream, prompts[], turn_running)
        self.sessions_lock = threading.Lock()
        self.conns = set()             # live MuxConnection objects
        self.conns_lock = threading.Lock()
        self.counter = Counter()
        self.session_seq = [0]         # monotonic session id generator


STATE = State()


def register_session(conn, sid, stream_id):
    with STATE.sessions_lock:
        sess = STATE.sessions.setdefault(sid, {
            "cwd": None, "conn": None, "stream": None,
            "prompts": [], "turn_running": False,
        })
        sess["conn"] = conn
        sess["stream"] = stream_id
        return sess


def get_session(sid):
    with STATE.sessions_lock:
        return STATE.sessions.get(sid)


def next_session_id():
    with STATE.sessions_lock:
        STATE.session_seq[0] += 1
        return "mock-session-%d" % STATE.session_seq[0]


# --------------------------------------------------------------------------
# Filesystem handling (docs/GATEWAY-API.md §2 — path semantics)
# --------------------------------------------------------------------------

class PathDenied(Exception):
    pass


def safe_join(rel):
    """Resolve rel under the workspace, enforcing the doc §2.4 traversal rules."""
    ws = CONF.workspace
    if rel is None:
        rel = ""
    if "\x00" in rel:
        raise PathDenied("NUL byte")
    rel = rel.replace("\\", "/")
    if rel.startswith("/") or re.match(r"^[A-Za-z]:", rel):
        raise PathDenied("absolute path")
    parts = [p for p in rel.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise PathDenied(".. traversal")
    target = os.path.normpath(os.path.join(ws, *parts)) if parts else ws
    # defense in depth: the resolved path must stay inside the workspace
    if os.path.commonpath([ws, target]) != ws:
        raise PathDenied("escapes workspace")
    if os.path.exists(target):
        real_t = os.path.realpath(target)
        real_w = os.path.realpath(ws)
        if os.path.commonpath([real_w, real_t]) != real_w:
            raise PathDenied("symlink escape")
    return target


def rel_of(abspath):
    return os.path.relpath(abspath, CONF.workspace)


MIME_MAP = {
    ".apk": "application/vnd.android.package-archive",
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".gif": "image/gif", ".txt": "text/plain", ".json": "application/json",
    ".html": "text/html", ".htm": "text/html", ".md": "text/markdown",
    ".xml": "application/xml", ".zip": "application/zip", ".bin": "application/octet-stream",
}


def mime_for(name):
    return MIME_MAP.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def build_disposition(name):
    safe = name.replace('"', '\\"')
    quoted = urllib.parse.quote(name, safe="")
    return 'attachment; filename="%s"; filename*=UTF-8\'\'%s' % (safe, quoted)


def list_entries(rel):
    """dirs first, then name-ascending; entry shape per doc §2.1."""
    target = safe_join(rel)
    if not os.path.isdir(target):
        return None
    entries = []
    for st_ent in os.scandir(target):
        try:
            is_dir = st_ent.is_dir()
            st = st_ent.stat()
        except OSError:
            continue
        name = st_ent.name
        entry = {
            "name": name,
            "path": rel_of(st_ent.path),
            "type": "directory" if is_dir else "file",
            "size": 0 if is_dir else st.st_size,
            "mtimeMs": int(st.st_mtime * 1000),
            "mime": None if is_dir else mime_for(name),
        }
        entries.append(entry)
    entries.sort(key=lambda e: (0 if e["type"] == "directory" else 1, e["name"]))
    return entries


def parse_multipart(body, boundary):
    """Hand-rolled, byte-safe multipart/form-data parser.

    Returns {field_name: {"filename": str|None, "content": bytes}}.
    The final CRLF before each boundary delimiter is framing, not content,
    so exactly one trailing CRLF is stripped from each part (RFC 2046).
    """
    delim = b"--" + boundary.encode("latin-1")
    idx = body.find(delim)
    if idx < 0:
        return {}
    fields = {}
    pos = idx + len(delim)
    while True:
        if pos + 2 > len(body):
            break
        if body[pos:pos + 2] == b"--":        # final boundary
            break
        if body[pos:pos + 2] != b"\r\n":
            break
        pos += 2
        hend = body.find(b"\r\n\r\n", pos)
        if hend < 0:
            break
        head = body[pos:hend].decode("latin-1")
        cstart = hend + 4
        nxt = body.find(delim, cstart)
        if nxt < 0:
            break
        content = body[cstart:nxt]
        if content.endswith(b"\r\n"):
            content = content[:-2]
        name = None
        filename = None
        for line in head.split("\r\n"):
            low = line.lower()
            if low.startswith("content-disposition:"):
                for piece in line.split(";")[1:]:
                    kv = piece.strip()
                    if kv.startswith("name="):
                        name = kv[5:].strip('"')
                    elif kv.lower().startswith("filename="):
                        filename = kv[9:].strip('"')
        if name is not None:
            fields[name] = {"filename": filename, "content": content}
        pos = nxt + len(delim)
    return fields


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


# --------------------------------------------------------------------------
# WebSocket mux connection (RFC 6455, per dsh-transport-spec.md §2/4/5/7)
# --------------------------------------------------------------------------

def build_data_frame(opcode, payload, masked=False):
    """Server frames are UNMASKED; the mock never sets the MASK bit."""
    b0 = 0x80 | opcode
    ln = len(payload)
    if ln < 126:
        head = bytes([b0, (0x80 | ln) if masked else ln])
    elif ln <= 0xFFFF:
        head = bytes([b0, (0x80 | 126) if masked else 126]) + struct.pack("!H", ln)
    else:
        head = bytes([b0, (0x80 | 127) if masked else 127]) + struct.pack("!Q", ln)
    if masked:
        m = os.urandom(4)
        body = bytes(b ^ m[i & 3] for i, b in enumerate(payload))
        return head + m + body
    return head + payload


class MuxConnection(object):
    """One accepted /api/remote.mux WebSocket connection (mock side)."""

    def __init__(self, handler):
        self.handler = handler
        self.rfile = handler.rfile
        self.wfile = handler.wfile
        self.write_lock = threading.Lock()
        self.alive = True
        self.streams = {}            # streamId -> endpoint
        self.client_id = None        # set when $events ready is sent
        self.frag_opcode = None
        self.frag_buf = b""
        self.was_masked = True       # sanity: every client frame must be masked
        # heartbeat bookkeeping
        self.pings_sent = 0
        self.pongs_received = 0
        self.last_ping_mono = time.monotonic()
        self.last_pong_mono = time.monotonic()
        # approval waterfall bookkeeping
        self.approval_event = threading.Event()
        self.approval_outcome = None

    # ---------------- low level I/O ----------------

    def _write(self, data):
        if not self.alive:
            raise OSError("mock mux connection is closed")
        with self.write_lock:
            self.wfile.write(data)
            self.wfile.flush()

    def send_text_raw(self, text):
        self._write(build_data_frame(0x1, text.encode("utf-8")))

    def send_ping(self, payload=b"hb"):
        self._write(build_data_frame(0x9, payload))

    def send_pong(self, payload):
        self._write(build_data_frame(0xA, payload))

    def send_close(self, code, reason=""):
        r = reason.encode("utf-8")[:123]
        self._write(build_data_frame(0x8, struct.pack("!H", code) + r))

    def close_with(self, code, reason):
        try:
            self.send_close(code, reason)
        except OSError:
            pass
        finally:
            self.alive = False
            self._close_socket()

    def _close_socket(self):
        self.alive = False
        try:
            self.handler.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.handler.connection.close()
        except OSError:
            pass

    def read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.rfile.read(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    # ---------------- outbound mux protocol ----------------

    def send_item(self, sid, value):
        self.send_text_raw(json.dumps({"type": "item", "streamId": sid, "value": value}))

    def send_end(self, sid):
        self.send_text_raw(json.dumps({"type": "end", "streamId": sid}))

    def send_error(self, sid, code, message):
        self.send_text_raw(json.dumps({
            "type": "error", "streamId": sid,
            "error": {"code": code, "message": message, "details": {}},
        }))

    # ---------------- inbound mux protocol ----------------

    def on_text_message(self, payload):
        try:
            msg = json.loads(payload.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            # real gateway: bad JSON -> close 1008 "invalid Remote stream request"
            log("bad JSON on mux; closing 1008 (matches real gateway behavior)")
            self.close_with(1008, "invalid Remote stream request")
            return
        if not isinstance(msg, dict):
            return
        mtype = msg.get("type")
        sid = msg.get("streamId")
        if mtype == "open":
            endpoint = msg.get("endpoint")
            args = (msg.get("payload") or {}).get("args") or {}
            self.streams[sid] = endpoint
            log("mux open streamId=%r endpoint=%r (TEST DOUBLE echo of the client's open frame)" % (sid, endpoint))
            if endpoint == "$events":
                self.client_id = "mock-client-1"
                self.send_item(sid, {
                    "type": "ready", "clientId": self.client_id,
                    "host": {"home": "/root"},
                })
                log("mux $events ready sent clientId=%s (host.home=/root — mock value, not a real user home)" % self.client_id)
            elif endpoint == "session/follow":
                request = args.get("request") or {}
                address = request.get("address") or {}
                target_session = address.get("sessionId")
                sess = register_session(self, target_session, sid)
                if sess["cwd"] is None:
                    sess["cwd"] = CONF.workspace
                # snapshot frame per spec §4a
                self.send_item(sid, {
                    "type": "snapshot", "cursor": 0, "records": [],
                    "hasMore": False, "projections": {},
                })
                log("mux session/follow snapshot sent for session=%r (records=0, hasMore=false)" % target_session)
                # if a prompt already arrived before the follow opened, drive it now
                pending = [p for p in sess["prompts"]] 
                if pending and not sess["turn_running"]:
                    sess["turn_running"] = True
                    threading.Thread(
                        target=run_scripted_turn,
                        args=(self, sid, target_session, pending[-1]),
                        daemon=True,
                    ).start()
            else:
                log("mux open for unknown endpoint %r — no scripted behaviour (TEST DOUBLE)" % endpoint)
        elif mtype == "cancel":
            if sid in self.streams:
                self.streams.pop(sid, None)
                self.send_end(sid)
                log("mux cancel streamId=%r -> end sent (per spec: cancel stops the stream)" % sid)
        else:
            log("mux ignored unknown client frame type=%r (protocol only defines open/cancel)" % mtype)

    # ---------------- reader loop ----------------

    def run(self):
        STATE.counter.inc("connections")
        with STATE.conns_lock:
            STATE.conns.add(self)
        log("WS connection accepted (pings every %.0fs, pong timeout %.0fs)" % (PING_INTERVAL_S, PONG_TIMEOUT_S))
        threading.Thread(target=self.ping_loop, daemon=True).start()
        try:
            while self.alive:
                h = self.read_exact(2)
                if h is None:
                    break
                b0, b1 = h[0], h[1]
                fin = bool(b0 & 0x80)
                rsv = (b0 >> 4) & 0x07
                opcode = b0 & 0x0F
                masked = bool(b1 & 0x80)
                ln = b1 & 0x7F
                if ln == 126:
                    e = self.read_exact(2)
                    if e is None:
                        break
                    ln = struct.unpack("!H", e)[0]
                elif ln == 127:
                    e = self.read_exact(8)
                    if e is None:
                        break
                    ln = struct.unpack("!Q", e)[0]
                if rsv != 0:
                    self.close_with(1002, "rsv bits set")
                    break
                if not masked:
                    # A compliant RFC 6455 client ALWAYS masks; the harness proves
                    # the Java client masks correctly by never tripping this.
                    STATE.counter.inc("unmasked")
                    log("*** UNMASKED CLIENT FRAME (opcode=%d len=%d) — a compliant client always masks; closing 1002" % (opcode, ln))
                    self.close_with(1002, "client frame not masked")
                    break
                mask = self.read_exact(4)
                if mask is None:
                    break
                raw = self.read_exact(ln)
                if raw is None:
                    break
                payload = bytes(b ^ mask[i & 3] for i, b in enumerate(raw))
                # ----- control frames (allowed interleaved) -----
                if opcode in (0x8, 0x9, 0xA):
                    if not fin or ln > 125:
                        self.close_with(1002, "invalid control frame")
                        break
                    if opcode == 0x9:                     # client ping -> pong
                        self.send_pong(payload)
                    elif opcode == 0xA:                   # pong from the client
                        self.pongs_received += 1
                        STATE.counter.inc("pongs")
                        self.last_pong_mono = time.monotonic()
                    else:                                  # close
                        code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1000
                        log("WS close received from client code=%d" % code)
                        try:
                            self.send_close(code)
                        except OSError:
                            pass
                        break
                    continue
                # ----- binary data frames are refused (real gateway: 1003) -----
                if opcode == 0x2:
                    STATE.counter.inc("binary")
                    log("*** BINARY CLIENT FRAME (len=%d) — closing 1003 'text messages required' (matches real gateway)" % ln)
                    self.close_with(1003, "text messages required")
                    break
                if opcode not in (0x0, 0x1):
                    self.close_with(1002, "unknown opcode")
                    break
                # ----- text / continuation handling -----
                if opcode == 0x0:
                    if self.frag_opcode is None:
                        self.close_with(1002, "continuation without a started message")
                        break
                    self.frag_buf += payload
                    if fin:
                        body, op = self.frag_buf, self.frag_opcode
                        self.frag_opcode, self.frag_buf = None, b""
                        if op == 0x1:
                            self.on_text_message(body)
                else:  # 0x1
                    if fin:
                        self.on_text_message(payload)
                    else:
                        self.frag_opcode = 0x1
                        self.frag_buf = payload
        except OSError as e:
            log("WS connection I/O ended: %s" % e)
        except Exception as e:  # never let one bad frame kill anything silently
            log("WS connection error (TEST DOUBLE): %r" % (e,))
        finally:
            self._finish()

    def _finish(self):
        self.alive = False
        self._close_socket()
        with STATE.conns_lock:
            STATE.conns.discard(self)
        with STATE.sessions_lock:
            for sess in STATE.sessions.values():
                if sess["conn"] is self:
                    sess["conn"] = None
                    sess["stream"] = None
        log("WS connection closed (mock side)")

    # ---------------- heartbeat loop ----------------

    def ping_loop(self):
        while self.alive:
            now = time.monotonic()
            if self.pings_sent > 0 and (now - self.last_pong_mono) > PONG_TIMEOUT_S:
                log("*** NO PONG within %.0fs (pings_sent=%d pongs_received=%d) — closing 1001 (heartbeat timeout); the Java client MUST answer every Ping with a Pong" % (
                    PONG_TIMEOUT_S, self.pings_sent, self.pongs_received))
                self.close_with(1001, "heartbeat timeout")
                break
            if (now - self.last_ping_mono) >= PING_INTERVAL_S:
                try:
                    self.send_ping(b"hb")
                    self.pings_sent += 1
                    STATE.counter.inc("pings")
                    self.last_ping_mono = now
                except OSError:
                    break
            time.sleep(0.25)


def run_scripted_turn(conn, sid, session_id, prompt_text):
    """The scripted turn, driven once session/prompt arrives (spec §7 Step 5-8)."""
    STATE.counter.inc("turns")
    log("TURN start session=%r prompt=%r (scripted by the TEST DOUBLE — NOT a real agent)" % (session_id, prompt_text))
    try:
        # 1. turn/start
        conn.send_item(sid, {"type": "event", "event": {"type": "turn/start", "seq": 1, "time": now_ms(), "data": {"turn": 0}}})
        # 2. assistant-stream start
        conn.send_item(sid, {"type": "assistant-stream", "frame": {
            "type": "start", "attemptId": "a1", "revision": 1,
            "startedAfterSeq": 1, "turn": 0, "step": 0,
        }})
        # 3. chunks with ~40 ms gaps
        for i, word in enumerate(CHUNK_WORDS):
            time.sleep(CHUNK_GAP_S)
            conn.send_item(sid, {"type": "assistant-stream", "frame": {
                "type": "chunk", "attemptId": "a1", "revision": 1,
                "index": i, "time": now_ms(),
                "chunk": {"type": "text-delta", "text": word},
            }})
            STATE.counter.inc("chunks_streamed")
        # 4. assistant-stream end
        conn.send_item(sid, {"type": "assistant-stream", "frame": {
            "type": "end", "attemptId": "a1", "revision": 1, "index": 0,
            "outcome": {"kind": "committed", "eventType": "assistant/message", "seq": 2},
        }})
        time.sleep(0.03)
        # 5. tool/call
        conn.send_item(sid, {"type": "event", "event": {
            "type": "tool/call", "seq": 3, "time": now_ms(),
            "data": {"turn": 0, "step": 0, "callId": "c1",
                     "name": "read_file", "arguments": '{"path":"README.md"}'},
        }})
        time.sleep(0.03)
        # 6. tool/result
        conn.send_item(sid, {"type": "event", "event": {
            "type": "tool/result", "seq": 4, "time": now_ms(),
            "data": {"turn": 0, "step": 0,
                     "message": {"role": "tool",
                                 "content": [{"type": "text", "text": "mock tool output"}]}},
        }})
        time.sleep(0.03)
        # 7. assistant/message (the same full sentence)
        conn.send_item(sid, {"type": "event", "event": {
            "type": "assistant/message", "seq": 5, "time": now_ms(),
            "data": {"turn": 0, "step": 0,
                     "message": {"role": "assistant",
                                 "content": [{"type": "text", "text": FULL_SENTENCE}]},
                     "stream": []},
        }})
        # 8. approval waterfall on the $events stream; BLOCK the turn until answered
        if CONF.approve:
            ev_sid = next((s for s, ep in conn.streams.items() if ep == "$events"), "ev")
            conn.send_item(ev_sid, {"type": "waterfall", "event": "approval/request",
                                    "eventId": "e1", "agentId": "mock-agent",
                                    "request": {"toolName": "shell", "reason": "mock approval", "callId": "c2"}})
            log("APPROVAL waterfall sent eventId=e1 on stream %r — BLOCKING the turn until $events/result (max %.0fs)" % (ev_sid, APPROVAL_TIMEOUT_S))
            conn.approval_event.clear()
            answered = conn.approval_event.wait(APPROVAL_TIMEOUT_S)
            if not answered:
                log("*** APPROVAL TIMEOUT: no $events/result within %.0fs — turn stayed blocked; error frame sent" % APPROVAL_TIMEOUT_S)
                conn.send_error(sid, "mock/approval-timeout", "no approval answer within %.0fs (TEST DOUBLE)" % APPROVAL_TIMEOUT_S)
                conn.send_end(ev_sid)
                conn.send_end(sid)
                return
            log("APPROVAL resolved; outcome logged verbatim by the $events/result handler")
        # 9. turn/end then end frames (both streams, so the harness sees clean ends)
        conn.send_item(sid, {"type": "event", "event": {
            "type": "turn/end", "seq": 6, "time": now_ms(),
            "data": {"turn": 0, "reason": "completed"},
        }})
        ev_sid = next((s for s, ep in conn.streams.items() if ep == "$events"), "ev")
        conn.send_end(ev_sid)
        conn.send_end(sid)
        log("TURN complete session=%r (chunks=%d, sentence=%r)" % (session_id, len(CHUNK_WORDS), FULL_SENTENCE))
    except OSError as e:
        log("TURN interrupted (client closed the mux or socket died): %s" % e)
    finally:
        sess = get_session(session_id)
        if sess is not None:
            sess["turn_running"] = False


# --------------------------------------------------------------------------
# HTTP layer
# --------------------------------------------------------------------------

class GatewayHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MockGatewayTestDouble/1.0"   # unmistakably a test double
    sys_version = ""

    # ------------ helpers ------------

    def bearer_token(self):
        auth = self.headers.get("Authorization")
        if auth and auth.lower().startswith("bearer "):
            return auth[7:].strip()
        return None

    def authorized(self, token):
        if not token:
            return False
        with STATE.issued_lock:
            return token in STATE.issued

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return b""
        return self.rfile.read(length)

    def respond_json(self, status, obj, extra_headers=None):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        if extra_headers:
            for k, v in extra_headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def respond_bytes(self, status, body, headers):
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # keep every HTTP access in the mock log with the TEST DOUBLE prefix
        log("http| " + (fmt % args))

    # ------------ routing ------------

    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        if path == MUX_PATH:
            self.handle_websocket_upgrade(parsed)
            return
        if path == "/gateway/health":
            self.respond_json(200, {"ok": True, "service": "mock-gateway", "version": "1.0.0",
                                    "configured": True, "uptimeMs": now_ms() - START_MS})
            return
        token = self.bearer_token()
        if not self.authorized(token):
            self.respond_json(401, {"ok": False, "error": "unauthorized"})
            return
        if path == "/gateway/session":
            expires = STATE_ISSUED_EXPIRES.get(token, now_ms() + CONF.session_max_age_ms)
            self.respond_json(200, {"ok": True, "username": CONF.user, "expiresAt": expires})
            return
        if path == "/fs/list":
            self.handle_fs_list(parsed)
            return
        if path == "/fs/download":
            self.handle_fs_download(parsed)
            return
        if path == "/fs/upload":
            self.respond_json(405, {"ok": False, "error": "method_not_allowed"})
            return
        self.respond_json(404, {"ok": False, "error": "not_found",
                                "message": "mock gateway (TEST DOUBLE): no such route"})

    def do_POST(self):
        # Always consume the entire request body BEFORE routing: otherwise an
        # unconsumed body stays buffered and corrupts the next request on this
        # keep-alive connection (the mock once answered a leftover "{}" body as
        # the start of the following GET, producing a 501 for /gateway/session).
        body = self.read_body()
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path
        if path == "/gateway/login":
            self.handle_login(body)
            return
        token = self.bearer_token()
        if not self.authorized(token):
            self.respond_json(401, {"ok": False, "error": "unauthorized"})
            return
        if path == "/gateway/logout":
            with STATE.issued_lock:
                STATE.issued.discard(token)
            log("token revoked by logout (presented bearer no longer valid — /gateway/session will now 401)")
            self.respond_json(200, {"ok": True})
            return
        if path == "/fs/upload":
            self.handle_fs_upload(body)
            return
        if path == "/fs/list" or path == "/fs/download":
            self.respond_json(405, {"ok": False, "error": "method_not_allowed"})
            return
        if path.startswith("/api/"):
            self.handle_rpc(path, body)
            return
        self.respond_json(404, {"ok": False, "error": "not_found",
                                "message": "mock gateway (TEST DOUBLE): no such route"})

    # ------------ /gateway/* ------------

    def handle_login(self, body):
        try:
            obj = json.loads(body.decode("utf-8"))
            username = obj.get("username")
            password = obj.get("password")
        except (ValueError, AttributeError):
            self.respond_json(400, {"ok": False, "error": "bad_request",
                                    "message": "body is not valid JSON or missing fields"})
            return
        if not isinstance(username, str) or not isinstance(password, str):
            self.respond_json(400, {"ok": False, "error": "bad_request",
                                    "message": "username and password must be strings"})
            return
        if username == CONF.user and password == CONF.password:
            exp = now_ms() + CONF.session_max_age_ms
            token = self.mint_token(username, exp)
            with STATE.issued_lock:
                STATE.issued.add(token)
            STATE_ISSUED_EXPIRES[token] = exp
            log("login OK user=%r (TEST DOUBLE issued token %s…)" % (username, token[:24]))
            self.respond_json(200, {"ok": True, "token": token, "username": username, "expiresAt": exp})
        else:
            # identical response for wrong user vs wrong password (doc §1.2 security)
            log("login FAILED user=%r (TEST DOUBLE returns invalid_credentials)" % username)
            self.respond_json(401, {"ok": False, "error": "invalid_credentials"})

    def mint_token(self, username, exp):
        payload = {"sub": username, "iat": now_ms(), "exp": exp,
                   "jti": os.urandom(8).hex()}
        body = base64.urlsafe_b64encode(json.dumps(payload).encode("utf-8")).rstrip(b"=")
        sig = base64.urlsafe_b64encode(
            hmac.new(CONF.token_secret, body, hashlib.sha256).digest()
        ).rstrip(b"=")
        return "mock.%s.%s" % (body.decode("ascii"), sig.decode("ascii"))

    # ------------ /fs/* ------------

    def handle_fs_list(self, parsed):
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        rel = (qs.get("path") or [""])[0]
        if rel in ("", "."):
            rel = ""
        try:
            entries = list_entries(rel)
        except PathDenied as e:
            log("fs/list path=%r DENIED: %s" % (rel, e))
            self.respond_json(403, {"ok": False, "error": "path_denied"})
            return
        if entries is None:
            log("fs/list path=%r NOT FOUND" % rel)
            self.respond_json(404, {"ok": False, "error": "not_found"})
            return
        resp_rel = rel if rel else "."
        log("fs/list path=%r -> %d entries" % (resp_rel, len(entries)))
        self.respond_json(200, {"ok": True, "path": resp_rel, "entries": entries})

    def handle_fs_download(self, parsed):
        qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        rel = (qs.get("path") or [""])[0]
        if rel in ("", "."):
            rel = ""
        try:
            target = safe_join(rel)
        except PathDenied as e:
            log("fs/download path=%r DENIED: %s" % (rel, e))
            self.respond_json(403, {"ok": False, "error": "path_denied"})
            return
        if not os.path.isfile(target):
            log("fs/download path=%r NOT FOUND" % rel)
            self.respond_json(404, {"ok": False, "error": "not_found"})
            return
        size = os.path.getsize(target)
        mtime_ms = int(os.stat(target).st_mtime * 1000)
        name = os.path.basename(target)
        etag = '"%d-%d"' % (size, mtime_ms)
        base = {
            "Content-Type": mime_for(name),
            "Content-Disposition": build_disposition(name),
            "Accept-Ranges": "bytes",
            "ETag": etag,
        }
        range_hdr = self.headers.get("Range")
        if not range_hdr:
            with open(target, "rb") as f:
                data = f.read()
            log("fs/download path=%r full 200 (%d bytes, etag=%s)" % (rel, size, etag))
            self.respond_bytes(200, data, base)
            return
        m = re.match(r"^bytes=(\d*)-(\d*)$", range_hdr.strip())
        start_s, end_s = (m.group(1), m.group(2)) if m else ("", "")
        ok = m is not None and start_s != ""
        if ok and end_s != "":
            ok = end_s.isdigit()
        if ok:
            s = int(start_s)
            e = size - 1 if end_s == "" else min(int(end_s), size - 1)
            if s >= size or s > e:
                ok = False
        if not ok:
            log("fs/download path=%r malformed Range %r -> 416" % (rel, range_hdr))
            self.respond_bytes(416, b"", {
                "Content-Range": "bytes */%d" % size,
                "Content-Type": "text/plain",
            })
            return
        with open(target, "rb") as f:
            f.seek(s)
            data = f.read(e - s + 1)
        hdrs = dict(base)
        hdrs["Content-Range"] = "bytes %d-%d/%d" % (s, e, size)
        log("fs/download path=%r Range bytes=%d- -> 206 (%d bytes of %d)" % (rel, s, e - s + 1, size))
        self.respond_bytes(206, data, hdrs)

    def handle_fs_upload(self, body):
        ct = self.headers.get("Content-Type", "")
        bm = re.search(r";\s*boundary=\"?([^\";]+)\"?", ct, re.IGNORECASE)
        if not ct.lower().startswith("multipart/form-data") or not bm:
            log("fs/upload rejected: not multipart/form-data (Content-Type=%r) -> 400" % ct)
            self.respond_json(400, {"ok": False, "error": "bad_request",
                                    "message": "Content-Type must be multipart/form-data with a boundary"})
            return
        fields = parse_multipart(body, bm.group(1))
        log("fs/upload multipart fields parsed: %s" % sorted(fields.keys()))
        file_field = fields.get("file")
        if file_field is None:
            self.respond_json(400, {"ok": False, "error": "bad_request", "message": "missing 'file' field"})
            return
        content = file_field["content"]
        filename = file_field["filename"] or "upload.bin"
        digest = sha256_hex(content)
        expected = self.headers.get("X-File-SHA256")
        if expected is not None and expected.strip().lower() != digest:
            log("fs/upload CHECKSUM MISMATCH (X-File-SHA256=%r != %s) -> 400" % (expected, digest))
            self.respond_json(400, {"ok": False, "error": "checksum_mismatch"})
            return
        path_field = fields.get("path", {}).get("content", b"").decode("utf-8", "replace")
        if path_field in ("", "."):
            path_field = ""
        try:
            if path_field:
                candidate = safe_join(path_field)
                if os.path.isdir(candidate):
                    target = os.path.join(candidate, filename)
                else:
                    parent = os.path.dirname(candidate)
                    if parent and not os.path.isdir(parent):
                        log("fs/upload parent dir missing: %r -> 404" % parent)
                        self.respond_json(404, {"ok": False, "error": "not_found"})
                        return
                    target = candidate
            else:
                target = os.path.join(CONF.workspace, filename)
            safe = os.path.abspath(target)
            if os.path.commonpath([CONF.workspace, safe]) != CONF.workspace:
                raise PathDenied("target escapes workspace")
        except PathDenied as e:
            log("fs/upload path=%r DENIED: %s" % (path_field, e))
            self.respond_json(403, {"ok": False, "error": "path_denied"})
            return
        # atomic write: temp file in the same directory, then os.replace
        tmp = "%s.part-%d.%08x" % (target, os.getpid(), int(time.time() * 1e6) & 0xFFFFFFFF)
        try:
            with open(tmp, "wb") as f:
                f.write(content)
            os.replace(tmp, target)
        except OSError as e:
            log("fs/upload write failed: %s" % e)
            self.respond_json(500, {"ok": False, "error": "internal"})
            return
        log("fs/upload OK -> %s (%d bytes, sha256=%s)" % (rel_of(target), len(content), digest))
        self.respond_json(200, {
            "ok": True, "completed": True, "receivedChunks": 1, "totalChunks": 1,
            "path": rel_of(target), "size": len(content), "sha256": digest,
        })

    # ------------ /api/<ns>/<method>  (JSON-RPC over HTTP) ------------

    def handle_rpc(self, path, body):
        ns_method = path[len("/api/"):]
        STATE.counter.inc("rpc_calls")
        mt = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
        if mt != "application/json":
            log("rpc %s rejected: Content-Type %r != application/json -> 415" % (ns_method, mt))
            self.respond_json(415, {"ok": False, "error": "bad_request",
                                    "message": "content type must be application/json"})
            return
        try:
            env = json.loads(body.decode("utf-8"))
        except ValueError:
            log("rpc %s rejected: body is not JSON -> 400" % ns_method)
            self.respond_json(400, {"ok": False, "error": "bad_request", "message": "body is not JSON"})
            return
        if not isinstance(env, dict) or env.get("type") != "client-request":
            log("rpc %s rejected: envelope is not a client-request -> 400" % ns_method)
            self.respond_json(400, {"ok": False, "error": "bad_request",
                                    "message": "envelope must be {type:'client-request',...}"})
            return
        rpc_id = env.get("rpcId")
        method = env.get("method")
        if method != ns_method:
            # This is the proof the Java client sends the right `method` field:
            # the real gateway returns gateway/bad-request (HTTP 200) on mismatch.
            log("*** METHOD MISMATCH: url endpoint=%r vs message.method=%r -> result.ok=false gateway/bad-request (HTTP 200)" % (ns_method, method))
            self.respond_rpc(rpc_id, False, {"code": "gateway/bad-request",
                                             "message": "method mismatch", "details": {}})
            return
        args = (env.get("payload") or {}).get("args") or {}
        if not isinstance(args, dict):
            args = {}
        # TEST DOUBLE hidden switch: force one endpoint to fail as a business error
        if CONF.fail_method == ns_method:
            log("*** --fail-method switch forces a business error on %s (TEST DOUBLE only)" % ns_method)
            self.respond_rpc(rpc_id, False, {"code": "gateway/error",
                                             "message": "forced failure by mock --fail-method (TEST DOUBLE switch; not the real gateway)",
                                             "details": {}})
            return
        try:
            value = self.dispatch_rpc(ns_method, args)
        except OSError:
            raise
        except Exception as e:
            log("rpc %s internal mock error: %r" % (ns_method, e))
            self.respond_rpc(rpc_id, False, {"code": "gateway/internal", "message": str(e), "details": {}})
            return
        if isinstance(value, tuple) and len(value) == 2 and value[0] is False:
            self.respond_rpc(rpc_id, False, value[1])
        else:
            self.respond_rpc(rpc_id, True, value)

    def respond_rpc(self, rpc_id, ok, payload):
        if ok:
            result = {"ok": True, "value": payload}
        else:
            result = {"ok": False, "error": payload}
        self.respond_json(200, {"type": "server-response", "rpcId": rpc_id, "result": result})
        log("rpc replied rpcId=%r ok=%s" % (rpc_id, ok))

    def dispatch_rpc(self, ns_method, args):
        if ns_method == "session/list":
            now = now_ms()
            items = [
                {"sessionId": "mock-past-1", "updatedAt": now - 60_000,
                 "running": False, "blank": False, "cwd": CONF.workspace},
                {"sessionId": "mock-past-2", "updatedAt": now - 5_000,
                 "running": False, "blank": True, "cwd": CONF.workspace},
            ]
            log("session/list -> %d synthetic sessions (TEST DOUBLE)" % len(items))
            return {"items": items}
        if ns_method == "session/create":
            request = args.get("request") or {}
            sid = next_session_id()
            cwd = request.get("cwd") or CONF.workspace
            with STATE.sessions_lock:
                sess = STATE.sessions.setdefault(sid, {
                    "cwd": cwd, "conn": None, "stream": None,
                    "prompts": [], "turn_running": False,
                })
                sess["cwd"] = cwd
            log("session/create -> %s (cwd echoed: %r)" % (sid, cwd))
            return {"sessionId": sid, "cwd": cwd}
        if ns_method == "session/prompt":
            request = args.get("request") or {}
            sid = request.get("sessionId")
            content = request.get("content") or []
            texts = [c.get("text", "") for c in content
                     if isinstance(c, dict) and c.get("type") == "text"]
            text = "".join(texts)
            STATE.counter.inc("prompts")
            sess = get_session(sid)
            if sess is None:
                sess = register_session(None, sid, None)
            sess["prompts"].append(text)
            log("PROMPT received session=%r text=%r (the TEST DOUBLE will now stream a scripted turn)" % (sid, text))
            conn, stream = sess["conn"], sess["stream"]
            if conn is not None and stream is not None and not sess["turn_running"]:
                sess["turn_running"] = True
                threading.Thread(target=run_scripted_turn,
                                 args=(conn, stream, sid, text), daemon=True).start()
            else:
                log("session/prompt for %r: follow stream not open yet (or turn running); prompt queued" % sid)
            return {"accepted": True}
        if ns_method == "session/cancel":
            log("session/cancel accepted (TEST DOUBLE)" )
            return {"accepted": True}
        if ns_method == "$events/result":
            client_id = args.get("clientId")
            event_id = args.get("eventId")
            outcome = args.get("outcome")
            STATE.counter.inc("approvals_answered")
            log("APPROVAL ANSWERED (raw outcome JSON): %s" % json.dumps(outcome))
            target = None
            with STATE.conns_lock:
                for c in STATE.conns:
                    if c.client_id and c.client_id == client_id:
                        target = c
                        break
            if target is None:
                log("$events/result: no mux connection with clientId=%r (TEST DOUBLE: nothing to unblock)" % client_id)
            else:
                target.approval_outcome = outcome
                target.approval_event.set()
                log("$events/result unblocked the approval waterfall for clientId=%r eventId=%r" % (client_id, event_id))
            return {"accepted": True}
        log("rpc unknown endpoint %r -> result.ok=false gateway/not-found (HTTP 200)" % ns_method)
        return (False, {"code": "gateway/not-found", "message": "unknown endpoint " + ns_method, "details": {}})

    # ------------ WebSocket upgrade ------------

    def handle_websocket_upgrade(self, parsed):
        token = self.bearer_token()
        if not self.authorized(token):
            STATE.counter.inc("handshake_failures")
            log("WS upgrade REJECTED: missing/invalid bearer token -> raw HTTP 401 (no upgrade)")
            body = b'{"ok":false,"error":"unauthorized"}'
            raw = ("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n"
                   "Content-Length: %d\r\nConnection: close\r\n\r\n" % len(body)).encode("ascii") + body
            try:
                self.wfile.write(raw)
                self.wfile.flush()
            except OSError:
                pass
            self.close_connection = True
            return
        upgrade = (self.headers.get("Upgrade") or "").strip().lower()
        conn_hdr = (self.headers.get("Connection") or "").lower()
        version = (self.headers.get("Sec-WebSocket-Version") or "").strip()
        key = (self.headers.get("Sec-WebSocket-Key") or "").strip()
        if upgrade != "websocket" or "upgrade" not in conn_hdr.split(",") or version != "13" or not key:
            STATE.counter.inc("handshake_failures")
            log("WS upgrade REJECTED: bad handshake (upgrade=%r connection=%r version=%r key=%r) -> 400" % (
                upgrade, conn_hdr, version, bool(key)))
            body = b'{"ok":false,"error":"bad_request","message":"invalid WebSocket handshake"}'
            raw = ("HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\n"
                   "Content-Length: %d\r\nConnection: close\r\n\r\n" % len(body)).encode("ascii") + body
            try:
                self.wfile.write(raw)
                self.wfile.flush()
            except OSError:
                pass
            self.close_connection = True
            return
        accept = base64.b64encode(hashlib.sha1(
            (key + WS_GUID).encode("utf-8")).digest()).decode("ascii")
        resp = ("HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                "Sec-WebSocket-Accept: %s\r\n"
                "Sec-WebSocket-Protocol: dsh.mux.v1\r\n"  # informational only
                "\r\n" % accept).encode("ascii")
        self.wfile.write(resp)
        self.wfile.flush()
        self.close_connection = True   # take over the raw socket; no more HTTP framing
        log("WS upgrade 101 OK (Sec-WebSocket-Accept computed from client key)")
        conn = MuxConnection(self)
        conn.run()


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

STATE_ISSUED_EXPIRES = {}   # token -> expiresAt (mirrors what login returned)


def print_summary():
    c = STATE.counter.snapshot()
    log("==================================================================")
    log(" MOCK GATEWAY EXIT SUMMARY — TEST DOUBLE (NOT the real gateway; proves")
    log(" nothing about the real dsh-remote-gateway or about real dsh)")
    log("==================================================================")
    log(" connections accepted   : %d" % c.get("connections", 0))
    log(" handshake failures     : %d" % c.get("handshake_failures", 0))
    log(" pings sent             : %d" % c.get("pings", 0))
    log(" pongs received         : %d" % c.get("pongs", 0))
    log(" unmasked client frames : %d   (MUST BE 0 — proves the Java client masks)" % c.get("unmasked", 0))
    log(" binary client frames   : %d   (must be 0)" % c.get("binary", 0))
    log(" rpc calls              : %d" % c.get("rpc_calls", 0))
    log(" prompts received       : %d" % c.get("prompts", 0))
    log(" chunks streamed        : %d" % c.get("chunks_streamed", 0))
    log(" turns scripted         : %d" % c.get("turns", 0))
    log(" approvals answered     : %d (raw outcomes were logged verbatim above)" % c.get("approvals_answered", 0))
    with STATE.sessions_lock:
        sessions = list(STATE.sessions.keys())
    log(" sessions seen          : %d %s" % (len(sessions), sessions))
    log("==================================================================")


def main(argv):
    global CONF
    CONF = parse_args(argv)
    os.makedirs(CONF.workspace, exist_ok=True)
    banner = [
        "##########################################################################",
        "# mock_gateway.py — TEST DOUBLE of the dsh-remote-gateway.              #",
        "# This is NOT the real dsh-remote-gateway and NOT real dsh. It proves    #",
        "# nothing about them; it exists only to execute the pure-Java harness.  #",
        "##########################################################################",
        "# user=%r port=%d host=%s workspace=%s approve=%s fail_method=%s" % (
            CONF.user, CONF.port, CONF.host, CONF.workspace, CONF.approve, CONF.fail_method),
    ]
    for line in banner:
        log(line)

    server = ThreadingHTTPServer((CONF.host, CONF.port), GatewayHandler)

    def _on_signal(signum, frame):
        log("received signal %d — shutting down, printing TEST DOUBLE summary" % signum)
        server._BaseServer__shutdown_request = True

    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)
    log("listening on %s:%d (SIGINT/SIGTERM stops and prints the exit summary)" % (CONF.host, CONF.port))
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        # unblock any wedged WS handler threads so the process can exit cleanly
        with STATE.conns_lock:
            for c in list(STATE.conns):
                try:
                    c._close_socket()
                except Exception:
                    pass
        time.sleep(0.2)
        print_summary()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))