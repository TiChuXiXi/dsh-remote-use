package com.dsh.remote.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workspace file browser over {@code /fs/list} with breadcrumb + Up navigation,
 * tap-a-file download into {@code getExternalFilesDir(DIRECTORY_DOWNLOADS)} (no runtime
 * permission needed) and a document-picker upload via {@code GatewayClient.upload}.
 */
public final class FilesActivity extends Activity {

    private static final int REQ_UPLOAD = 1001;

    private TextView breadcrumbView;
    private TextView statusView;
    private Button upButton;
    private Button uploadButton;
    private ListView listView;
    private FilesAdapter adapter;

    private final List<Object> entries = new ArrayList<Object>();
    private String currentPath = "";

    private ExecutorService executor;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        executor = Executors.newFixedThreadPool(2, App.daemonThreads("files"));
        buildUi();
        refresh("");
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

        breadcrumbView = new TextView(this);
        breadcrumbView.setTextSize(16);
        breadcrumbView.setTypeface(breadcrumbView.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(breadcrumbView, fill());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        upButton = new Button(this);
        upButton.setText("Up");
        row.addView(upButton, weight(1));

        uploadButton = new Button(this);
        uploadButton.setText("Upload");
        row.addView(uploadButton, weight(1));

        root.addView(row, fill());

        statusView = new TextView(this);
        statusView.setTextSize(13);
        root.addView(statusView, fill());

        adapter = new FilesAdapter();
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(listView);

        setContentView(root);

        upButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FilesActivity.this.refresh(parentOf(currentPath));
            }
        });
        uploadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FilesActivity.this.pickUpload();
            }
        });
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                FilesActivity.this.onEntryTap(position);
            }
        });
    }

    // -------------------------------------------------------------- actions

    private void refresh(final String path) {
        statusView.setText("Loading " + (path.isEmpty() ? "/" : path) + "\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Object> items = FilesActivity.this.gc().fsList(path);
                    FilesActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            currentPath = path;
                            entries.clear();
                            entries.addAll(items);
                            sortEntries(entries);
                            adapter.notifyDataSetChanged();
                            breadcrumbView.setText(breadcrumb(path));
                            upButton.setEnabled(!path.isEmpty());
                            statusView.setText(entries.size() + " entries");
                        }
                    });
                } catch (Exception e) {
                    FilesActivity.this.status("list failed: " + messageOf(e));
                }
            }
        });
    }

    private void onEntryTap(int position) {
        Object o = entries.get(position);
        Map<String, Object> e = App.asMap(o);
        if (e == null) {
            return;
        }
        String type = Json.str(e, "type");
        String rel = Json.str(e, "path");
        String name = Json.str(e, "name");
        if (rel == null) {
            return;
        }
        if ("directory".equals(type)) {
            refresh(rel);
        } else {
            download(rel, name == null ? "download" : name);
        }
    }

    private void download(final String relPath, final String rawName) {
        final String name = sanitizeName(rawName);
        statusView.setText("Downloading " + name + "\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = FilesActivity.this.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) {
                        FilesActivity.this.status("download failed: no external files dir");
                        return;
                    }
                    final File dest = new File(dir, name);
                    FilesActivity.this.gc().download(relPath, dest, true);
                    FilesActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            statusView.setText("Saved: " + dest.getAbsolutePath());
                            App.toast(FilesActivity.this, dest.getAbsolutePath());
                        }
                    });
                } catch (Exception e) {
                    FilesActivity.this.status("download failed: " + messageOf(e));
                }
            }
        });
    }

    private void pickUpload() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_UPLOAD);
        } catch (Exception e) {
            status("no file picker available: " + messageOf(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_UPLOAD && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                upload(uri);
            }
        }
    }

    private void upload(final Uri uri) {
        final String displayName = displayName(uri);
        statusView.setText("Uploading " + displayName + "\u2026");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                File tmp = null;
                try {
                    InputStream in = FilesActivity.this.getContentResolver().openInputStream(uri);
                    if (in == null) {
                        FilesActivity.this.status("upload failed: cannot open picked file");
                        return;
                    }
                    tmp = new File(FilesActivity.this.getCacheDir(), "upload-" + System.currentTimeMillis());
                    OutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    try {
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (IOException ignore) {
                            // already closed
                        }
                        try {
                            out.close();
                        } catch (IOException ignore) {
                            // already closed
                        }
                    }
                    final String rel = sanitizeName(displayName);
                    FilesActivity.this.gc().upload(rel, tmp);
                    FilesActivity.this.status("uploaded " + rel);
                } catch (Exception e) {
                    FilesActivity.this.status("upload failed: " + messageOf(e));
                } finally {
                    if (tmp != null) {
                        tmp.delete();
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------- adapter

    private final class FilesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = new LinearLayout(FilesActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            int p = App.dp(FilesActivity.this, 10);
            row.setPadding(p, p, p, p);

            TextView title = new TextView(FilesActivity.this);
            title.setTextSize(15);
            TextView sub = new TextView(FilesActivity.this);
            sub.setTextSize(12);

            Map<String, Object> e = App.asMap(getItem(position));
            String name = e == null ? "?" : Json.str(e, "name");
            String type = e == null ? "" : Json.str(e, "type");
            long size = e == null ? 0L : Json.num(e, 0L, "size");

            title.setText((name == null ? "?" : name)
                    + ("directory".equals(type) ? "/" : ""));
            sub.setText("directory".equals(type)
                    ? "directory"
                    : (size < 0 ? "" : humanSize(size)));

            row.addView(title);
            row.addView(sub);
            return row;
        }
    }

    // ------------------------------------------------------------ helpers

    private GatewayClient gc() {
        GatewayClient c = new GatewayClient(App.getBaseUrl(this));
        c.http().setBearer(App.getToken(this));
        return c;
    }

    private void status(final String text) {
        FilesActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                statusView.setText(text);
            }
        });
    }

    private String displayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignore) {
            // fall back to a generated name
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (name == null || name.isEmpty()) {
            name = "upload-" + System.currentTimeMillis();
        }
        return name;
    }

    private static String sanitizeName(String raw) {
        if (raw == null) {
            return "download";
        }
        String s = raw.replace('/', '_').replace('\\', '_').trim();
        return s.isEmpty() ? "download" : s;
    }

    private static String parentOf(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int i = path.lastIndexOf('/');
        if (i <= 0) {
            return "";
        }
        return path.substring(0, i);
    }

    private static String breadcrumb(String path) {
        if (path == null || path.isEmpty()) {
            return "workspace root";
        }
        return path.replace("/", " / ");
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        if (bytes < 1024L * 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        return (bytes / (1024L * 1024 * 1024)) + " GB";
    }

    private static void sortEntries(List<Object> items) {
        Collections.sort(items, new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                Map<String, Object> ma = App.asMap(a);
                Map<String, Object> mb = App.asMap(b);
                String ta = ma == null ? "" : Json.str(ma, "type");
                String tb = mb == null ? "" : Json.str(mb, "type");
                boolean da = "directory".equals(ta);
                boolean db = "directory".equals(tb);
                if (da != db) {
                    return da ? -1 : 1;
                }
                String na = ma == null ? "" : Json.str(ma, "name");
                String nb = mb == null ? "" : Json.str(mb, "name");
                if (na == null) {
                    na = "";
                }
                if (nb == null) {
                    nb = "";
                }
                return na.compareTo(nb);
            }
        });
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.toString() : m;
    }

    private static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }
}