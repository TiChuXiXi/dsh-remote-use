package com.dsh.remote.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.Json;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workspace file browser over {@code /fs/list} with breadcrumb + Up navigation, tap-a-file
 * download and a document-picker upload.
 *
 * <p><b>Downloads</b>: on API 29+ the file is published into the public
 * {@code MediaStore.Downloads} collection, so it shows up in the system Downloads app; on
 * API 26–28 (where {@code MediaStore.Downloads} does not exist) it falls back to
 * {@code getExternalFilesDir(DIRECTORY_DOWNLOADS)}. Neither route needs a runtime storage
 * permission, so the app requests none — in particular <b>not</b>
 * {@code WRITE_EXTERNAL_STORAGE}.</p>
 *
 * <p><b>Uploads</b>: small files go as a single multipart request (mode A); files at or above
 * {@link #CHUNKED_UPLOAD_THRESHOLD_BYTES} go as 4 MiB chunks (mode B) with per-chunk progress
 * in the status line.</p>
 */
public final class FilesActivity extends Activity {

    private static final int REQ_UPLOAD = 1001;

    /**
     * Size at which uploads switch from mode A (one multipart request) to mode B (4 MiB
     * chunks). The tradeoff:
     *
     * <ul>
     *   <li>Mode A is a single round trip and is what the verified small-file path uses, but
     *       the gateway stages the whole body in {@code stateDir} and then copies it to the
     *       target directory (root README, 已知限制), and a failure anywhere means re-sending
     *       the entire file.</li>
     *   <li>Mode B writes each chunk straight into the chunk session and merges once, and a
     *       failed chunk only costs that chunk — but it costs one request per 4 MiB.</li>
     * </ul>
     *
     * 8 MiB (two chunks) is where the cost of "one failure re-sends everything" starts to
     * outweigh the cost of extra requests. Below it, mode A is left exactly as it was.
     */
    private static final long CHUNKED_UPLOAD_THRESHOLD_BYTES = 8L * 1024 * 1024;

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
                    final String savedAt;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        savedAt = downloadIntoPublicDownloads(relPath, name);
                    } else {
                        savedAt = downloadIntoAppDir(relPath, name);
                    }
                    FilesActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) {
                                return;
                            }
                            statusView.setText("Saved: " + savedAt);
                            App.toast(FilesActivity.this, savedAt);
                        }
                    });
                } catch (Exception e) {
                    FilesActivity.this.status("download failed: " + messageOf(e));
                }
            }
        });
    }

    /**
     * API 26–28 fallback: app-private external storage. Needs no runtime permission, but the
     * file is invisible to the system Downloads app (and to every other app).
     *
     * @return the absolute path shown to the user
     */
    private String downloadIntoAppDir(String relPath, String name) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new IOException("no external files dir");
        }
        File dest = new File(dir, name);
        gc().download(relPath, dest, true);
        return dest.getAbsolutePath();
    }

    /**
     * API 29+: insert a row into the public {@code MediaStore.Downloads} collection, stream the
     * file into it, then publish it by clearing {@code IS_PENDING}.
     *
     * <p><b>Why a temp file instead of downloading straight into the MediaStore stream:</b>
     * {@code openOutputStream} hands back a write-only, non-seekable stream, while Range resume
     * needs both the bytes already on disk <em>and</em> their length to build
     * {@code Range: bytes=<n>-}. So resume keeps using the verified
     * {@code Http.downloadTo(path, File, resume)} against a {@code .part-} staging file, and the
     * finished file is copied into the MediaStore stream afterwards. That costs one extra full
     * copy (the same tradeoff mode-A uploads already make on the gateway side) and buys an
     * unchanged, still-verified resume path for large downloads.</p>
     *
     * <p><b>Failure:</b> the row is inserted with {@code IS_PENDING=1}, which already hides it
     * from the system Downloads app, and is deleted outright if anything fails — no broken or
     * zero-byte row is left behind. The staging file is kept on purpose so the next attempt can
     * resume it.</p>
     *
     * @return the user-visible path ("Download/&lt;name&gt;")
     */
    private String downloadIntoPublicDownloads(String relPath, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(name));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri row = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (row == null) {
            throw new IOException("MediaStore refused the download row for " + name);
        }
        try {
            File part = stagingFileFor(name);
            gc().download(relPath, part, true);   // resume: the verified File-based path
            OutputStream out = cr.openOutputStream(row, "w");
            if (out == null) {
                throw new IOException("MediaStore gave no output stream for " + name);
            }
            try {
                copyFile(part, out);
            } finally {
                try {
                    out.close();
                } catch (IOException ignore) {
                    // already closed / nothing to do
                }
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(row, published, null, null);
            part.delete();
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        } catch (Exception e) {
            // No half-written, zero-byte or unpublished-but-orphaned row: drop it.
            try {
                cr.delete(row, null, null);
            } catch (RuntimeException ignore) {
                // the row is gone already, or MediaStore is unavailable — nothing more to do
            }
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("MediaStore download failed: " + messageOf(e), e);
        }
    }

    /**
     * The staging file for a MediaStore download, named {@code .part-<name>} so it can never be
     * mistaken for a finished download. Lives in the app-private dir (resumable across app
     * restarts, no permission) and falls back to the internal cache when external storage is
     * not mounted.
     */
    private File stagingFileFor(String name) throws IOException {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = getCacheDir();
        }
        if (dir == null) {
            throw new IOException("no writable directory for the download");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        return new File(dir, ".part-" + name);
    }

    private static void copyFile(File src, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    /**
     * MIME type for the MediaStore row. {@code MimeTypeMap} is Android's own table; the
     * {@code URLConnection} guess and finally {@code application/octet-stream} cover
     * extensions Android's table does not know.
     */
    private static String mimeTypeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null && !mime.isEmpty()) {
                return mime;
            }
        }
        String guessed = java.net.URLConnection.guessContentTypeFromName(name);
        return (guessed == null || guessed.isEmpty()) ? "application/octet-stream" : guessed;
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
                    final long size = tmp.length();
                    if (size >= CHUNKED_UPLOAD_THRESHOLD_BYTES) {
                        // Mode B: one request per 4 MiB chunk, progress in the status line.
                        // The array is the "last progress seen" holder the failure message
                        // needs — anonymous inner classes cannot write a captured local.
                        final int[] sent = new int[] {0, 0};
                        GatewayClient.UploadProgress progress = new GatewayClient.UploadProgress() {
                            @Override
                            public void chunkSent(int chunkIndex, int totalChunks,
                                                  long bytesSent, long totalBytes) {
                                sent[0] = chunkIndex;
                                sent[1] = totalChunks;
                                FilesActivity.this.status("Uploading " + rel + "\u2026 "
                                        + chunkIndex + "/" + totalChunks + " chunks ("
                                        + humanSize(bytesSent) + " of " + humanSize(totalBytes) + ")");
                            }
                        };
                        try {
                            FilesActivity.this.gc().uploadChunked(rel, tmp, rel, progress);
                        } catch (IOException e) {
                            // Surface how far the upload got: the file itself is discarded
                            // (it is a cache copy), so the chunk count is the only progress
                            // the user can act on.
                            throw new IOException(messageOf(e) + "（已发送 " + sent[0] + "/"
                                    + sent[1] + " 块）", e);
                        }
                    } else {
                        // Mode A: unchanged single multipart request.
                        // 第三个参数是 multipart 的 filename。tmp 是个缓存临时文件
                        // （upload-<millis>），不传真实名字的话，wire 上写的会是那个临时名。
                        // 服务端以 path 字段为准所以结果本来就对，但日志/下游工具会记错名字。
                        FilesActivity.this.gc().upload(rel, tmp, rel);
                    }
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