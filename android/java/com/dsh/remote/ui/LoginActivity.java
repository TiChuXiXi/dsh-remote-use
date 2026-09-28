package com.dsh.remote.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.dsh.remote.net.GatewayClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher activity. Server URL / username / password form plus a status line.
 * On create, a stored token is validated in the background via
 * {@link GatewayClient#checkSession()}; on success the user goes straight to
 * {@link SessionListActivity}.
 */
public final class LoginActivity extends Activity {

    private EditText urlInput;
    private EditText userInput;
    private EditText passInput;
    private Button loginButton;
    private TextView statusView;

    private ExecutorService executor;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        executor = Executors.newFixedThreadPool(2, App.daemonThreads("login"));
        buildUi();
        prefill();
        maybeAutoLogin();
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
        int pad = App.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("DSH Remote");
        title.setTextSize(22);
        root.addView(title, wrap());

        urlInput = new EditText(this);
        urlInput.setHint("https://gw.example.com");
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlInput, fill());

        userInput = new EditText(this);
        userInput.setHint("username");
        userInput.setSingleLine(true);
        userInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        root.addView(userInput, fill());

        passInput = new EditText(this);
        passInput.setHint("password");
        passInput.setSingleLine(true);
        passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passInput, fill());

        loginButton = new Button(this);
        loginButton.setText("Login");
        root.addView(loginButton, fill());

        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, wrap());

        setContentView(root);

        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LoginActivity.this.doLogin();
            }
        });
    }

    private void prefill() {
        urlInput.setText(App.getBaseUrl(this));
        userInput.setText(App.getUsername(this));
        if (!urlInput.getText().toString().isEmpty()) {
            urlInput.setSelection(urlInput.getText().length());
        }
    }

    // -------------------------------------------------------------- actions

    /** If a token is stored, check it in the background and skip login when valid. */
    private void maybeAutoLogin() {
        final String base = App.getBaseUrl(this);
        final String token = App.getToken(this);
        if (base.isEmpty() || token == null || token.isEmpty()) {
            return;
        }
        statusView.setText("Checking stored session\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    GatewayClient gc = new GatewayClient(base);
                    gc.http().setBearer(token);
                    ok = gc.checkSession();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final boolean valid = ok;
                final String detail = err;
                LoginActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        if (valid) {
                            LoginActivity.this.goToSessions();
                        } else {
                            statusView.setText("Stored session expired, please log in"
                                    + (detail != null ? " (" + detail + ")" : ""));
                        }
                    }
                });
            }
        });
    }

    private void doLogin() {
        final String base = urlInput.getText().toString().trim();
        final String user = userInput.getText().toString().trim();
        final String pass = passInput.getText().toString();
        if (base.isEmpty() || user.isEmpty()) {
            statusView.setText("Server URL and username are required");
            return;
        }
        loginButton.setEnabled(false);
        statusView.setText("Logging in\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    GatewayClient gc = new GatewayClient(base);
                    GatewayClient.LoginResult result = gc.login(user, pass);
                    App.saveLogin(LoginActivity.this, base, result.username, result.token, result.expiresAt);
                    LoginActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                LoginActivity.this.goToSessions();
                            }
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    LoginActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            loginButton.setEnabled(true);
                            statusView.setText("Login failed: " + msg);
                        }
                    });
                }
            }
        });
    }

    private void goToSessions() {
        startActivity(new Intent(this, SessionListActivity.class));
        finish();
    }

    // ------------------------------------------------------------ helpers

    private static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}