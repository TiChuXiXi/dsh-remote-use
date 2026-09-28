package com.dsh.remote.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists sessions via {@code session/list}. Each row shows the shortened session id,
 * cwd, running state and relative updatedAt. Tapping a row opens {@link ChatActivity}.
 */
public final class SessionListActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "session_id";

    private ListView listView;
    private SessionAdapter adapter;
    private TextView statusView;
    private Button refreshButton;
    private Button newSessionButton;
    private Button filesButton;
    private Button logoutButton;

    private final List<Object> sessions = new ArrayList<Object>();
    private ExecutorService executor;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        executor = Executors.newFixedThreadPool(2, App.daemonThreads("sessions"));
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------- UI build

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = App.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        buttons.addView(refreshButton, weight(1));

        newSessionButton = new Button(this);
        newSessionButton.setText("New session");
        buttons.addView(newSessionButton, weight(1));

        filesButton = new Button(this);
        filesButton.setText("Files");
        buttons.addView(filesButton, weight(1));

        logoutButton = new Button(this);
        logoutButton.setText("Logout");
        buttons.addView(logoutButton, weight(1));

        root.addView(buttons, fill());

        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, fill());

        adapter = new SessionAdapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listView);

        setContentView(root);

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionListActivity.this.refresh();
            }
        });
        newSessionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionListActivity.this.promptNewSession();
            }
        });
        filesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionListActivity.this.startActivity(new Intent(SessionListActivity.this, FilesActivity.class));
            }
        });
        logoutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionListActivity.this.doLogout();
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                SessionListActivity.this.openChat(position);
            }
        });
    }

    // -------------------------------------------------------------- actions

    private void refresh() {
        statusView.setText("Refreshing\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Object> items = App.rpc(SessionListActivity.this).sessionList();
                    SessionListActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            sessions.clear();
                            sessions.addAll(items);
                            adapter.notifyDataSetChanged();
                            statusView.setText(items.size() + " session(s)");
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    SessionListActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            statusView.setText("Refresh failed: " + msg);
                        }
                    });
                }
            }
        });
    }

    private void promptNewSession() {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("New session");
        final EditText cwdInput = new EditText(this);
        cwdInput.setHint("working directory (empty = omit)");
        b.setView(cwdInput);
        b.setPositiveButton("Create", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String raw = cwdInput.getText().toString().trim();
                final String cwd = raw.isEmpty() ? null : raw;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final String sid = App.rpc(SessionListActivity.this).sessionCreate(cwd);
                            SessionListActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (destroyed) {
                                        return;
                                    }
                                    SessionListActivity.this.openChat(sid);
                                }
                            });
                        } catch (Exception e) {
                            final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            SessionListActivity.this.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (!destroyed) {
                                        statusView.setText("Create failed: " + msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
        b.setNegativeButton("Cancel", null);
        b.show();
    }

    private void doLogout() {
        logoutButton.setEnabled(false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    GatewayClient gc = new GatewayClient(App.getBaseUrl(SessionListActivity.this));
                    gc.http().setBearer(App.getToken(SessionListActivity.this));
                    gc.logout();
                } catch (Exception ignore) {
                    // best-effort: local logout must still happen
                }
                SessionListActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        App.clearLogin(SessionListActivity.this);
                        Intent i = new Intent(SessionListActivity.this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        SessionListActivity.this.startActivity(i);
                        SessionListActivity.this.finish();
                    }
                });
            }
        });
    }

    private void openChat(int position) {
        Object entry = sessions.get(position);
        Map<String, Object> m = App.asMap(entry);
        String sid = m == null ? null : Json.str(m, "sessionId");
        if (sid == null || sid.isEmpty()) {
            return;
        }
        openChat(sid);
    }

    private void openChat(String sessionId) {
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra(EXTRA_SESSION_ID, sessionId));
    }

    // ------------------------------------------------------------- adapter

    private final class SessionAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sessions.size();
        }

        @Override
        public Object getItem(int position) {
            return sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(SessionListActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            int p = App.dp(SessionListActivity.this, 10);
            row.setPadding(p, p, p, p);

            TextView title = new TextView(SessionListActivity.this);
            title.setTextSize(15);
            TextView sub = new TextView(SessionListActivity.this);
            sub.setTextSize(12);

            Map<String, Object> m = App.asMap(getItem(position));
            String sid = m == null ? null : Json.str(m, "sessionId");
            String shortId = shortSessionId(sid);
            boolean running = m != null && Json.bool(m, false, "running");
            String cwd = m == null ? null : Json.str(m, "cwd");
            long updatedAt = m == null ? 0L : Json.num(m, 0L, "updatedAt");

            title.setText(shortId + (running ? "  \u25CF running" : ""));
            sub.setText((cwd == null || cwd.isEmpty() ? "(no cwd)" : cwd)
                    + "  \u00B7  " + relativeTime(updatedAt));

            row.addView(title);
            row.addView(sub);
            return row;
        }
    }

    // ------------------------------------------------------------ helpers

    private static String shortSessionId(String sid) {
        if (sid == null || sid.isEmpty()) {
            return "?";
        }
        if (sid.length() > 12) {
            return sid.substring(0, 8) + "\u2026";
        }
        return sid;
    }

    private static String relativeTime(long epochMs) {
        if (epochMs <= 0) {
            return "?";
        }
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 0) {
            diff = 0;
        }
        long s = diff / 1000L;
        if (s < 60) {
            return s + "s ago";
        }
        long m = s / 60L;
        if (m < 60) {
            return m + "m ago";
        }
        long h = m / 60L;
        if (h < 24) {
            return h + "h ago";
        }
        return (h / 24L) + "d ago";
    }

    private static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }
}