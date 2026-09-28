package com.dsh.remote.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.dsh.remote.net.DshRpcClient;
import com.dsh.remote.net.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat with one session. Opens the shared mux on the background executor for BOTH
 * {@code session/follow} (streamId "s1", assistantStream enabled) and {@code $events}
 * (streamId "ev"), renders assistant-stream chunk frames incrementally, durable
 * {@code event} frames (user/message, assistant/message, tool/call, tool/result,
 * turn/end, approval/asked) and answers {@code approval/request} waterfalls with an
 * AlertDialog whose outcome is posted via {@code $events/result}.
 *
 * A tool approval blocks the host turn, so it is ALWAYS answered (Allow once / Reject,
 * dialog not cancelable; if the activity is already destroyed the request is auto-rejected).
 */
public final class ChatActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private String sessionId;

    private ScrollView scrollView;
    private TextView transcriptView;
    private EditText inputView;
    private Button sendButton;
    private Button cancelButton;
    private TextView statusView;

    private ExecutorService executor;
    private volatile boolean destroyed = false;

    private volatile DshRpcClient rpcRef;
    private volatile DshRpcClient.Mux mux;
    private volatile String clientId;

    /** Only touched from the mux reader thread: open between assistant-stream start/end. */
    private boolean liveSectionOpen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null) {
            sessionId = "";
        }
        executor = Executors.newFixedThreadPool(2, App.daemonThreads("chat"));
        buildUi();
        setTitle("Session " + shortId(sessionId));
        openStreams();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (mux != null) {
            try {
                mux.close();
            } catch (RuntimeException ignore) {
                // nothing else to do at teardown
            }
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------- UI build

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = App.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        transcriptView = new TextView(this);
        transcriptView.setTextSize(13);
        transcriptView.setTypeface(Typeface.MONOSPACE);
        transcriptView.setTextIsSelectable(true);
        scrollView.addView(transcriptView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView);

        inputView = new EditText(this);
        inputView.setHint("Message");
        inputView.setSingleLine(true);
        root.addView(inputView, fill());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        sendButton = new Button(this);
        sendButton.setText("Send");
        row.addView(sendButton, weight(1));

        cancelButton = new Button(this);
        cancelButton.setText("Cancel turn");
        row.addView(cancelButton, weight(1));

        root.addView(row, fill());

        statusView = new TextView(this);
        statusView.setTextSize(12);
        root.addView(statusView, fill());

        setContentView(root);

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatActivity.this.doSend();
            }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ChatActivity.this.doCancel();
            }
        });
    }

    // ------------------------------------------------------------ streams

    private void openStreams() {
        status("Opening streams\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (destroyed || sessionId.isEmpty()) {
                    if (destroyed) {
                        return;
                    }
                    ChatActivity.this.status("no session id");
                    return;
                }
                try {
                    rpcRef = App.rpc(ChatActivity.this);
                    mux = rpcRef.mux();
                    rpcRef.followSession("s1", sessionId, followListener);
                    rpcRef.openEvents("ev", eventsListener);
                    ChatActivity.this.status("streams open");
                } catch (Exception e) {
                    ChatActivity.this.status("stream open failed: " + messageOf(e));
                }
            }
        });
    }

    private final DshRpcClient.StreamListener followListener = new DshRpcClient.StreamListener() {
        @Override
        public void onValue(Object value) {
            handleFollowValue(value);
        }

        @Override
        public void onError(Object error) {
            status("follow stream error: " + describe(error));
        }

        @Override
        public void onEnd() {
            // stream closed by the host; nothing to render
        }
    };

    private final DshRpcClient.StreamListener eventsListener = new DshRpcClient.StreamListener() {
        @Override
        public void onValue(Object value) {
            handleEventsValue(value);
        }

        @Override
        public void onError(Object error) {
            status("events stream error: " + describe(error));
        }

        @Override
        public void onEnd() {
            // stream closed by the host; nothing to render
        }
    };

    // ----------------------------------------- session/follow frame handling

    private void handleFollowValue(Object value) {
        Map<String, Object> m = App.asMap(value);
        if (m == null) {
            return;
        }
        String type = Json.str(m, "type");
        if (type == null) {
            return;
        }
        if ("event".equals(type)) {
            Map<String, Object> ev = App.asMap(m.get("event"));
            if (ev != null) {
                handleEventFrame(ev);
            }
        } else if ("assistant-stream".equals(type)) {
            Map<String, Object> frame = App.asMap(m.get("frame"));
            if (frame != null) {
                handleAssistantFrame(frame);
            }
        } else if ("snapshot".equals(type)) {
            int n = 0;
            Object records = m.get("records");
            if (records instanceof List) {
                n = ((List<?>) records).size();
            }
            appendTranscript("\n[snapshot: " + n + " records]");
        }
        // unknown frame shapes are ignored without crashing
    }

    private void handleAssistantFrame(Map<String, Object> frame) {
        String type = Json.str(frame, "type");
        if ("start".equals(type)) {
            liveSectionOpen = true;
        } else if ("chunk".equals(type)) {
            if (liveSectionOpen) {
                String text = chunkText(frame.get("chunk"));
                if (text != null && !text.isEmpty()) {
                    appendLive(text);
                }
            }
        } else if ("end".equals(type)) {
            liveSectionOpen = false;
        }
    }

    /**
     * The chunk payload is usually {@code {"type":"text-delta","text":"..."}}; also accept
     * {@code {"type":"text","text":...}}, a map carrying a plain {@code text} key, or a bare
     * string. Anything else yields null (defensively ignored).
     */
    private static String chunkText(Object chunk) {
        if (chunk instanceof String) {
            return (String) chunk;
        }
        Map<String, Object> m = App.asMap(chunk);
        if (m == null) {
            return null;
        }
        return Json.str(m, "text");
    }

    private void handleEventFrame(Map<String, Object> event) {
        String type = Json.str(event, "type");
        if (type == null) {
            return;
        }
        Map<String, Object> data = App.asMap(event.get("data"));
        if ("user/message".equals(type)) {
            String txt = messageText(data);
            appendTranscript("\nYOU: " + (txt == null ? "(message)" : txt));
        } else if ("assistant/message".equals(type)) {
            String txt = messageText(data);
            appendTranscript("\nassistant: " + (txt == null ? "(message)" : txt));
        } else if ("tool/call".equals(type)) {
            if (data == null) {
                return;
            }
            String name = Json.str(data, "name");
            String args = Json.str(data, "arguments");
            if (args == null) {
                Object a = data.get("arguments");
                if (a != null) {
                    args = Json.stringify(a);
                }
            }
            appendTranscript("\nTOOL CALL " + (name == null ? "?" : name) + " " + trim(args, 200));
        } else if ("tool/result".equals(type)) {
            if (data == null) {
                return;
            }
            appendTranscript("\n" + trim(toolResultText(data), 200));
        } else if ("turn/end".equals(type)) {
            long turn = data == null ? -1L : Json.num(data, -1L, "turn");
            String reason = data == null ? null : Json.str(data, "reason");
            appendTranscript("\n--- turn " + (turn < 0 ? "?" : turn) + " ended ("
                    + (reason == null ? "?" : reason) + ") ---");
        } else if ("approval/asked".equals(type)) {
            if (data == null) {
                return;
            }
            appendTranscript("\n[approval asked: id=" + nullable(Json.str(data, "id"))
                    + " tool=" + nullable(Json.str(data, "toolName"))
                    + " reason=" + nullable(Json.str(data, "reason")) + "]");
        }
        // every other durable event type is ignored without crashing
    }

    // ------------------------------------------- $events frame handling

    private void handleEventsValue(Object value) {
        Map<String, Object> m = App.asMap(value);
        if (m == null) {
            return;
        }
        String type = Json.str(m, "type");
        if ("ready".equals(type)) {
            clientId = Json.str(m, "clientId");
            appendTranscript("\n[events connected"
                    + (clientId != null ? " (client " + clientId + ")" : "") + "]");
        } else if ("waterfall".equals(type)) {
            String event = Json.str(m, "event");
            String eventId = Json.str(m, "eventId");
            if ("approval/request".equals(event)) {
                showApprovalDialog(m, eventId);
            } else {
                appendTranscript("\n[waterfall " + nullable(event) + "]");
            }
        }
        // emit/cancel frames and unknown shapes are ignored without crashing
    }

    /** Shows the pending approval in the transcript and pops a non-cancelable dialog. */
    private void showApprovalDialog(final Map<String, Object> frame, final String eventId) {
        Map<String, Object> req = App.asMap(frame.get("request"));
        String tool = req == null ? null : Json.str(req, "toolName");
        if (tool == null && req != null) {
            tool = Json.str(req, "name");
        }
        final String toolName = tool;
        final String reason = req == null ? null : Json.str(req, "reason");
        appendTranscript("\n[approval pending: " + nullable(toolName)
                + (reason != null && !reason.isEmpty() ? " \u2014 " + reason : "") + "]");

        ChatActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    // The activity is gone but the host turn is still blocked: auto-reject.
                    ChatActivity.this.answerApproval(eventId, false);
                    return;
                }
                if (eventId == null) {
                    ChatActivity.this.status("approval without eventId, cannot answer");
                    return;
                }
                AlertDialog.Builder b = new AlertDialog.Builder(ChatActivity.this);
                b.setTitle("Tool approval requested");
                b.setMessage("Tool: " + (toolName == null ? "unknown" : toolName)
                        + (reason != null && !reason.isEmpty() ? "\nReason: " + reason : ""));
                b.setCancelable(false);
                b.setPositiveButton("Allow once", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ChatActivity.this.answerApproval(eventId, true);
                    }
                });
                b.setNegativeButton("Reject", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ChatActivity.this.answerApproval(eventId, false);
                    }
                });
                b.show();
            }
        });
    }

    private void answerApproval(final String eventId, final boolean allow) {
        final String cid = clientId;
        if (cid == null || eventId == null) {
            status("cannot answer approval: missing client/event id");
            return;
        }
        final Map<String, Object> outcome = buildOutcome(allow);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    App.rpc(ChatActivity.this).eventsResult(cid, eventId, outcome);
                    ChatActivity.this.status("approval answered (" + (allow ? "allow once" : "rejected") + ")");
                } catch (Exception e) {
                    ChatActivity.this.status("approval answer failed: " + messageOf(e));
                }
            }
        });
    }

    private static Map<String, Object> buildOutcome(boolean allow) {
        if (allow) {
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("kind", "next");
            return o;
        }
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("code", "user-rejected");
        err.put("message", "rejected on phone");
        Map<String, Object> o = new LinkedHashMap<String, Object>();
        o.put("kind", "rejected");
        o.put("error", err);
        return o;
    }

    // ------------------------------------------------------------ actions

    private void doSend() {
        final String text = inputView.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        inputView.setText("");
        appendTranscript("\nYOU: " + text);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    App.rpc(ChatActivity.this).sessionPrompt(sessionId, text);
                } catch (Exception e) {
                    ChatActivity.this.status("send failed: " + messageOf(e));
                }
            }
        });
    }

    private void doCancel() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    App.rpc(ChatActivity.this).sessionCancel(sessionId);
                    ChatActivity.this.status("cancel sent");
                } catch (Exception e) {
                    ChatActivity.this.status("cancel failed: " + messageOf(e));
                }
            }
        });
    }

    // ------------------------------------------------------------ helpers

    /** Appends a durable transcript line (marshal: mux reader thread to main thread). */
    private void appendTranscript(final String text) {
        ChatActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                transcriptView.append(text);
                ChatActivity.this.scrollDown();
            }
        });
    }

    /** Appends a live assistant token (marshal: mux reader thread to main thread). */
    private void appendLive(final String text) {
        ChatActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                transcriptView.append(text);
                ChatActivity.this.scrollDown();
            }
        });
    }

    private void status(final String text) {
        ChatActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                statusView.setText(text);
            }
        });
    }

    private void scrollDown() {
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    /** Extracts human text from an event's {@code data} object, walking it defensively. */
    private static String messageText(Object data) {
        Map<String, Object> dm = App.asMap(data);
        if (dm == null) {
            if (data instanceof String) {
                return (String) data;
            }
            return null;
        }
        Object msg = dm.get("message");
        if (msg == null) {
            msg = data;
        }
        return contentText(msg);
    }

    /** Walks {@code content}: a string, or a list of parts each possibly {@code {"type":"text","text":...}}. */
    private static String contentText(Object msg) {
        if (msg instanceof String) {
            return (String) msg;
        }
        Map<String, Object> mm = App.asMap(msg);
        if (mm == null) {
            return null;
        }
        Object content = mm.get("content");
        if (content == null) {
            content = mm.get("text");
        }
        if (content == null) {
            return Json.str(mm, "text");
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<?>) content) {
                if (part instanceof String) {
                    sb.append((String) part);
                    continue;
                }
                Map<String, Object> pm = App.asMap(part);
                if (pm == null) {
                    continue;
                }
                String t = Json.str(pm, "text");
                if (t != null) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return Json.str(mm, "text");
    }

    /** tool/result: prefer extracted text, else a compact JSON rendering of the message. */
    private static String toolResultText(Object data) {
        Object msg = data instanceof Map ? ((Map<?, ?>) data).get("message") : null;
        String text = messageText(data);
        if (text == null || text.isEmpty()) {
            if (msg != null) {
                try {
                    text = Json.stringify(msg);
                } catch (RuntimeException e) {
                    text = String.valueOf(msg);
                }
            }
        }
        return text == null ? "(tool result)" : text;
    }

    private static String describe(Object error) {
        if (error == null) {
            return "unknown";
        }
        try {
            Map<String, Object> m = App.asMap(error);
            if (m != null) {
                String code = Json.str(m, "code");
                String msg = Json.str(m, "message");
                if (code != null || msg != null) {
                    return (code == null ? "" : code) + (msg != null ? ": " + msg : "");
                }
            }
        } catch (RuntimeException ignore) {
            // fall through
        }
        return String.valueOf(error);
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.toString() : m;
    }

    private static String nullable(String s) {
        return s == null ? "?" : s;
    }

    private static String shortId(String s) {
        if (s == null || s.isEmpty()) {
            return "?";
        }
        if (s.length() > 12) {
            return s.substring(0, 8) + "\u2026";
        }
        return s;
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\u2026";
    }

    private static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }
}