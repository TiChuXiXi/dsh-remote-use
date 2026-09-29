package com.dsh.remote.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.Http;
import com.dsh.remote.net.Json;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Drives {@link FilesActivity}: the {@code /fs/list} browser, breadcrumb/Up navigation, a
 * real download onto disk (public {@code MediaStore.Downloads} on API 29+, app-private
 * external storage on 26–28), and real uploads that go through
 * {@code ContentResolver.openInputStream} into either a genuine multipart POST (mode A) or a
 * genuine chunked POST sequence (mode B).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// conscrypt-openjdk-uber ships no linux-aarch_64 native library, and Robolectric
// installs it as a JCE provider during setUpApplicationState. Nothing here uses TLS
// to a real peer, so switch it off rather than fail on UnsatisfiedLinkError.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FilesActivityTest extends UiTestBase {

    private static final String AUTHORITY = "com.dsh.remote.testfiles";
    private static final Uri PICKED = Uri.parse("content://" + AUTHORITY + "/picked.txt");
    private static final String PICKED_NAME = "picked.txt";
    private static final String PICKED_BODY = "uploaded-from-a-picked-document";

    /** Just over {@code FilesActivity}'s 8 MiB mode-B threshold: 4 MiB + 4 MiB + 1 byte. */
    private static final String BIG_NAME = "big.bin";
    private static final int BIG_SIZE = 8 * 1024 * 1024 + 1;
    private static final int BIG_CHUNKS = 3;

    private StubGateway gw;
    private TestMediaStoreProvider mediaStore;
    private File mediaDir;

    @Before
    public void setUp() throws Exception {
        gw = newStub();
        prefs().edit().clear().commit();
        // The provider itself is declared in app/src/test/AndroidManifest.xml so that it has
        // a real authority; Robolectric instantiates it from the merged manifest.
        TestFileProvider.install(PICKED_BODY);
        // The picked document is served entirely through Robolectric's ShadowContentResolver:
        // registerInputStream() supplies the bytes, and a hand-built ContentProvider supplies
        // the DISPLAY_NAME the Activity reads. attachInfo() must come first — without it the
        // provider has no authority and ContentResolver.query() throws
        // "The authority ... does not match the one of the contentProvider: null".
        // No test AndroidManifest.xml is needed for any of this.
        TestFileProvider provider = new TestFileProvider();
        provider.attachInfo(appContext(), new android.content.pm.ProviderInfo());
        Shadows.shadowOf(appContext().getContentResolver())
                .registerProviderInternal(AUTHORITY, provider);
        Shadows.shadowOf(appContext().getContentResolver()).registerInputStream(PICKED,
                new ByteArrayInputStream(PICKED_BODY.getBytes(
                        java.nio.charset.Charset.forName("UTF-8"))));

        // Robolectric has no system MediaStore, so the authority the production code inserts
        // into ("media") is served by a test provider — the same technique the SAF picker
        // double above uses. Registered for every test; on API < 29 nothing must touch it,
        // which several tests assert.
        mediaDir = new File(appContext().getCacheDir(), "mediastore-" + System.nanoTime());
        assertTrue("cannot create the fake MediaStore dir", mediaDir.mkdirs());
        mediaStore = new TestMediaStoreProvider(appContext(), mediaDir);
        mediaStore.attachInfo(appContext(), new ProviderInfo());
        Shadows.shadowOf(appContext().getContentResolver())
                .registerProviderInternal(TestMediaStoreProvider.AUTHORITY, mediaStore);
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.close();
        }
        ShadowContentResolver.reset();
        deleteRecursively(mediaDir);
    }

    // ------------------------------------------------------------------ rendering

    @Test
    public void rendersTheFileListSortedWithDirectoriesFirst() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();

        assertNotNull("Up button missing", findButton(activity, "Up"));
        assertNotNull("Upload button missing", findButton(activity, "Upload"));
        assertNotNull("breadcrumb missing", findTextView(activity, "workspace root"));

        assertTrue("/fs/list was never called", waitForRequests(gw, 1));
        assertEquals("the stored bearer must be sent",
                "Bearer tok", gw.request(0).header("Authorization"));
        assertEquals("the root listing must be requested with an empty path",
                "", gw.request(0).param("path"));

        assertTrue("the entry count never reached the status line, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "4 entries"));

        ListView list = findListView(activity);
        List<String> rows = renderedRowText(list);
        assertEquals(4, rows.size());
        assertTrue("directories must sort first, got: " + rows,
                rows.get(0).startsWith("docs/"));
        assertTrue("directories must sort first, got: " + rows, rows.get(1).startsWith("src/"));
        assertTrue("files follow, sorted by name, got: " + rows, rows.get(2).startsWith("a.txt"));
        assertTrue("files follow, sorted by name, got: " + rows, rows.get(3).startsWith("z.bin"));
        assertTrue("a file row should show a human size, got: " + rows, rows.get(2).contains("1 KB"));

        Button up = findButton(activity, "Up");
        assertFalse("Up must be disabled at the workspace root", up.isEnabled());

        controller.pause().stop().destroy();
    }

    @Test
    public void listingFailureIsReportedNotThrown() {
        gw.setHandler(ex -> StubGateway.Response.status(503, "not_configured"));
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();

        assertTrue(waitForRequests(gw, 1));
        assertTrue("expected a 'list failed' status, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("list failed"), 20_000L, "list failed"));
        assertFalse(activity.isFinishing());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ navigation

    @Test
    public void tappingADirectoryNavigatesIntoItAndUpComesBack() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "root entries"));

        ListView list = findListView(activity);
        // "docs" is row 0 after the directory-first sort
        list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);

        assertTrue("tapping a directory did not refetch", waitForRequests(gw, 2));
        assertEquals("docs", gw.request(1).param("path"));
        assertTrue("the breadcrumb never updated, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("docs"), 20_000L, "breadcrumb"));
        assertTrue(waitFor(() -> allText(activity).contains("2 entries"), 20_000L, "docs entries"));
        assertTrue("Up must be enabled inside a directory", findButton(activity, "Up").isEnabled());

        findButton(activity, "Up").performClick();
        assertTrue("Up did not refetch the parent", waitForRequests(gw, 3));
        assertEquals("", gw.request(2).param("path"));
        assertTrue("the breadcrumb never returned to the root",
                waitFor(() -> allText(activity).contains("workspace root"), 20_000L, "root crumb"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ download

    /**
     * The API 26–28 path: {@code MediaStore.Downloads} does not exist before API 29, so the
     * download must land in app-private external storage — and MediaStore must not be touched.
     *
     * <p>This test used to run at the class's default {@code sdk = 34}; it is pinned to 28 now
     * that API 29+ goes to the public Downloads collection. Its assertions are unchanged: the
     * file is still downloaded to disk with the same bytes, at the same path.</p>
     */
    @Test
    @Config(sdk = 28)
    public void tappingAFileDownloadsItOntoDisk() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "root entries"));

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0); // a.txt

        assertTrue("no /fs/download request was made",
                waitFor(() -> !gw.requestsFor("/fs/download").isEmpty(), 20_000L, "download"));
        StubGateway.Request dl = gw.requestsFor("/fs/download").get(0);
        assertEquals("a.txt", dl.param("path"));

        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        assertNotNull("no external files dir under Robolectric", dir);
        File dest = new File(dir, "a.txt");
        assertTrue("the download never reached the status line, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("Saved: " + dest.getAbsolutePath()),
                        20_000L, "Saved: " + dest.getAbsolutePath()));

        assertTrue("the file was not written to disk", dest.isFile());
        assertEquals("downloaded bytes differ", "hello-download", readFile(dest));
        assertEquals("below API 29 the public collection must not be touched at all",
                0, mediaStore.insertCount());

        controller.pause().stop().destroy();
    }

    @Test
    public void aDroppedDownloadConnectionIsReportedNotThrown() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/")) {
                return StubGateway.rpcOk(req, Json.obj());
            }
            if (req.path.startsWith("/fs/list")) {
                return StubGateway.Response.json(listBody());
            }
            if (req.path.startsWith("/fs/download")) {
                return StubGateway.Response.dropConnection();
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0);

        assertTrue("expected a 'download failed' status, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("download failed"), 20_000L, "failure"));
        assertFalse(activity.isFinishing());

        controller.pause().stop().destroy();
    }

    /**
     * A non-2xx download must be reported as a failure, not as "Saved:".
     *
     * <p>This was a real bug: {@code Http.doDownload} only writes the body for 2xx, and
     * {@code GatewayClient.download} did not inspect the status, so a {@code 403 path_denied}
     * left no file at all while the UI said {@code Saved: <path>} and raised a Toast. Fixed
     * by making {@code GatewayClient.download} throw on a non-2xx status.
     */
    @Test
    public void nonTwoHundredDownloadIsReportedAsFailure() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/")) {
                return StubGateway.rpcOk(req, Json.obj());
            }
            if (req.path.startsWith("/fs/list")) {
                return StubGateway.Response.json(listBody());
            }
            if (req.path.startsWith("/fs/download")) {
                return StubGateway.Response.status(403, "path_denied");
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0);

        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File dest = new File(dir, "a.txt");
        assertTrue("expected a 'download failed' status, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("download failed"), 20_000L, "failure"));
        // It must not claim success, and it must not leave a bogus zero-byte file behind.
        assertFalse("a failed download must not be reported as Saved",
                allText(activity).contains("Saved: "));
        assertFalse("a non-2xx download must not create the destination file", dest.exists());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------- download → MediaStore

    /**
     * API 29+ (the class default is 34): the download must be published into the public
     * {@code MediaStore.Downloads} collection with the right display name and MIME type, and
     * the bytes must be readable back out of the row's stream.
     */
    @Test
    public void tappingAFileDownloadsIntoThePublicDownloadsCollection() throws Exception {
        assertPublicDownload();
    }

    /** The same, pinned to API 29 — the first level at which {@code MediaStore.Downloads} exists. */
    @Test
    @Config(sdk = 29)
    public void mediaStoreDownloadWorksOnApi29() throws Exception {
        assertPublicDownload();
    }

    private void assertPublicDownload() throws Exception {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "root entries"));

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0); // a.txt

        assertTrue("no /fs/download request was made",
                waitFor(() -> !gw.requestsFor("/fs/download").isEmpty(), 20_000L, "download"));
        assertTrue("the MediaStore row was never published, text was:\n" + allText(activity),
                waitFor(() -> mediaStore.publishedRows().size() == 1, 20_000L, "published row"));

        TestMediaStoreProvider.Row row = mediaStore.publishedRows().get(0);
        assertEquals("the row must have been inserted through the media authority",
                TestMediaStoreProvider.AUTHORITY, row.uri.getAuthority());
        assertEquals("DISPLAY_NAME", "a.txt",
                row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME));
        assertEquals("MIME_TYPE", "text/plain",
                row.values.getAsString(MediaStore.Downloads.MIME_TYPE));
        assertEquals("RELATIVE_PATH", Environment.DIRECTORY_DOWNLOADS,
                row.values.getAsString(MediaStore.Downloads.RELATIVE_PATH));
        assertEquals("IS_PENDING must be cleared once the bytes are in place", 0,
                row.values.getAsInteger(MediaStore.Downloads.IS_PENDING).intValue());
        assertEquals("the row must have been inserted pending (invisible) first", 1,
                row.pendingAtInsert);
        assertEquals("the bytes written through the MediaStore stream differ", "hello-download",
                new String(mediaStore.bytesOf(row), java.nio.charset.Charset.forName("UTF-8")));
        // Evidence for the record: the row as the fake provider saw it.
        System.out.println("[FilesActivityTest] MediaStore row " + row.uri + " values="
                + row.values + " (IS_PENDING at insert=" + row.pendingAtInsert + ")");

        assertTrue("the status line must report where it went, text was:\n" + allText(activity),
                allText(activity).contains("Saved: Download/a.txt"));
        assertFalse("the app-private copy must not be left behind on API 29+",
                new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "a.txt")
                        .exists());

        controller.pause().stop().destroy();
    }

    /**
     * A failed download must not leave a row behind — not even an unpublished one. The row is
     * inserted with {@code IS_PENDING=1} (invisible to the system Downloads app) and deleted
     * outright when the transfer fails.
     */
    @Test
    public void aFailedDownloadLeavesNoMediaStoreRowBehind() {
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/fs/list")) {
                return StubGateway.Response.json(listBody());
            }
            if (req.path.startsWith("/fs/download")) {
                return StubGateway.Response.status(403, "path_denied");
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0);

        assertTrue("expected a 'download failed' status, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("download failed"), 20_000L, "failure"));
        assertFalse("a failed download must not be reported as Saved",
                allText(activity).contains("Saved: "));
        assertEquals("a row was inserted before the transfer", 1, mediaStore.insertCount());
        assertEquals("the failed row must have been deleted, not left pending", 0,
                mediaStore.rows().size());
        assertEquals("the row must have been deleted", 1, mediaStore.deleteCount());

        controller.pause().stop().destroy();
    }

    /**
     * Range resume must survive the MediaStore route. With a partial {@code .part-} staging file
     * already on disk, the download must ask for the rest with {@code Range: bytes=<n>-}, append
     * the 206 body to that file, and publish the complete bytes into the public collection.
     *
     * <p>This is the assertion behind the "download to a temp file, then copy into MediaStore"
     * design: downloading straight into the MediaStore stream would have to re-fetch the prefix,
     * because that stream cannot be seeked or appended to.</p>
     */
    @Test
    public void aRetriedMediaStoreDownloadResumesWithARangeHeader() throws Exception {
        final String full = "hello-download";
        final int alreadyHave = 5;                       // ".part-a.txt" holds "hello"
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/")) {
                return StubGateway.rpcOk(req, Json.obj());
            }
            if (req.path.startsWith("/fs/list")) {
                return StubGateway.Response.json(listBody());
            }
            if (req.path.startsWith("/fs/download")) {
                if (req.header("Range") == null) {
                    return StubGateway.Response.bytes(
                            full.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                            "application/octet-stream");
                }
                StubGateway.Response r = StubGateway.Response.bytes(
                        full.substring(alreadyHave).getBytes(java.nio.charset.Charset.forName("UTF-8")),
                        "application/octet-stream");
                r.status = 206;
                r.reason = "Partial Content";
                r.headers.put("Content-Range", "bytes " + alreadyHave + "-" + (full.length() - 1)
                        + "/" + full.length());
                return r;
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        // A previous, interrupted attempt: half of the file is already staged.
        File part = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                ".part-a.txt");
        OutputStream out = new FileOutputStream(part);
        out.write(full.substring(0, alreadyHave)
                .getBytes(java.nio.charset.Charset.forName("UTF-8")));
        out.close();

        ListView list = findListView(activity);
        list.performItemClick(list.getAdapter().getView(2, null, list), 2, 0);

        assertTrue("the row was never published, text was:\n" + allText(activity),
                waitFor(() -> mediaStore.publishedRows().size() == 1, 20_000L, "published row"));

        List<StubGateway.Request> downloads = gw.requestsFor("/fs/download");
        assertEquals("the resumed request must ask for the rest of the file",
                "bytes=" + alreadyHave + "-", downloads.get(0).header("Range"));
        assertEquals("the 206 must be appended to the staged bytes", full,
                new String(mediaStore.bytesOf(mediaStore.publishedRows().get(0)),
                        java.nio.charset.Charset.forName("UTF-8")));
        assertFalse("the staging file must be gone after a successful publish", part.exists());

        controller.pause().stop().destroy();
    }

    /**
     * The app must not ask for {@code WRITE_EXTERNAL_STORAGE} (or any storage permission):
     * {@code MediaStore.Downloads} needs none on API 29+, and the 26–28 fallback writes to
     * {@code getExternalFilesDir}, which needs none either.
     *
     * <p>The INTERNET assertion is a positive control — if Robolectric ever stops reporting the
     * merged manifest's permissions, this test must fail loudly instead of passing vacuously.</p>
     */
    @Test
    public void theAppRequiresNoStoragePermission() throws Exception {
        PackageInfo info = appContext().getPackageManager()
                .getPackageInfo(appContext().getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> requested = (info.requestedPermissions == null)
                ? Collections.<String>emptyList()
                : Arrays.asList(info.requestedPermissions);
        assertTrue("positive control: the manifest's INTERNET permission must be visible here, "
                + "otherwise this test proves nothing; got " + requested,
                requested.contains(Manifest.permission.INTERNET));
        assertFalse("WRITE_EXTERNAL_STORAGE must not be required: " + requested,
                requested.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        assertFalse("READ_EXTERNAL_STORAGE must not be required: " + requested,
                requested.contains(Manifest.permission.READ_EXTERNAL_STORAGE));
    }

    // ------------------------------------------------------------------ upload

    @Test
    public void uploadButtonStartsTheDocumentPicker() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();

        findButton(activity, "Upload").performClick();

        Intent started = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertNotNull("Upload started nothing", started);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.getAction());
        assertEquals("*/*", started.getType());

        controller.pause().stop().destroy();
    }

    @Test
    public void pickingADocumentUploadsItsBytesAndName() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));

        findButton(activity, "Upload").performClick();

        // receiveResult matches the STARTED intent, so reuse the very object we captured
        Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        Intent result = new Intent();
        result.setData(PICKED);
        Shadows.shadowOf(activity).receiveResult(request, Activity.RESULT_OK, result);

        assertTrue("no /fs/upload request was made",
                waitFor(() -> !gw.requestsFor("/fs/upload").isEmpty(), 20_000L, "upload"));
        int idx = gw.requests().indexOf(gw.requestsFor("/fs/upload").get(0));
        String contentType = gw.request(idx).header("Content-Type");
        assertTrue("expected a multipart body, got: " + contentType,
                contentType != null && contentType.startsWith("multipart/form-data; boundary="));
        String raw = new String(gw.rawBody(idx), java.nio.charset.Charset.forName("UTF-8"));
        assertTrue("the picked file's bytes must be in the multipart body, got:\n" + raw,
                raw.contains(PICKED_BODY));
        // The `path` field carries the picked display name ...
        assertTrue("the relative path field must be present, got:\n" + raw,
                raw.contains("name=\"path\""));
        assertTrue("the path field should hold the picked display name, got:\n" + raw,
                raw.contains("name=\"path\"\r\n\r\n" + PICKED_NAME));
        // ... and so must the multipart filename. This was a real bug: upload() copies the
        // picked document to a `upload-<millis>` cache file and handed that File straight to
        // GatewayClient, so the wire body said filename="upload-1790661938194". Fixed by
        // passing the real name through. See README §5.
        assertTrue("the file part must be named after the picked document, got:\n" + raw,
                raw.contains("name=\"file\"; filename=\"" + PICKED_NAME + "\""));
        assertFalse("the multipart filename must not be the cache temp name, got:\n" + raw,
                raw.contains("filename=\"upload-"));
        // Mode A must stay mode A for small files: no chunk headers at all.
        assertNull("a small file must not be sent as chunks, got X-Upload-Id: "
                        + gw.request(idx).header("X-Upload-Id"),
                gw.request(idx).header("X-Upload-Id"));
        assertNull("a small file must not carry X-Chunk-Index",
                gw.request(idx).header("X-Chunk-Index"));

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ upload, mode B

    /**
     * A file at or above the 8 MiB threshold must go as chunks: the same headers the contract
     * requires, one request per 4 MiB chunk, the concatenated bodies byte-identical to the
     * picked document, and the intermediate progress visible in the status line.
     */
    @Test
    public void pickingALargeDocumentUploadsItInChunksWithVisibleProgress() {
        final byte[] big = patternedBytes(BIG_SIZE);
        installPickedDocument(BIG_NAME, big);
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        // Every text this Activity ever shows, so the transient progress line can be asserted.
        List<String> seen = watchAllText(activity);

        findButton(activity, "Upload").performClick();
        Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        Intent result = new Intent();
        result.setData(PICKED);
        Shadows.shadowOf(activity).receiveResult(request, Activity.RESULT_OK, result);

        // NB: the failure message must be built after waitFor has run, otherwise it always
        // reports the count from before the wait ("saw 0").
        boolean gotChunks = waitFor(() -> gw.requestsFor("/fs/upload").size() >= BIG_CHUNKS,
                60_000L, "chunks");
        assertTrue("expected " + BIG_CHUNKS + " chunk requests, saw "
                + gw.requestsFor("/fs/upload").size(), gotChunks);
        assertTrue("the upload never finished, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("uploaded " + BIG_NAME), 60_000L, "uploaded"));

        List<StubGateway.Request> uploads = gw.requestsFor("/fs/upload");
        assertEquals("8 MiB + 1 byte is 4 MiB + 4 MiB + 1 byte", BIG_CHUNKS, uploads.size());

        String uploadId = uploads.get(0).header("X-Upload-Id");
        assertNotNull("X-Upload-Id is required for mode B", uploadId);
        long[] expectedLengths = new long[] {
                GatewayClient.CHUNK_SIZE_BYTES, GatewayClient.CHUNK_SIZE_BYTES, 1L};
        for (int i = 0; i < BIG_CHUNKS; i++) {
            StubGateway.Request r = uploads.get(i);
            assertEquals("X-Chunk-Index must increase monotonically from 0",
                    String.valueOf(i), r.header("X-Chunk-Index"));
            assertEquals("X-Total-Chunks must be the same on every chunk",
                    String.valueOf(BIG_CHUNKS), r.header("X-Total-Chunks"));
            assertEquals("all chunks must share one X-Upload-Id", uploadId, r.header("X-Upload-Id"));
            assertEquals("X-File-Path must be the url-encoded target",
                    Http.urlEncode(BIG_NAME), r.header("X-File-Path"));
            assertEquals("X-File-Name", BIG_NAME, r.header("X-File-Name"));
            assertEquals("mode B must not be multipart", "application/octet-stream",
                    r.header("Content-Type"));
            assertEquals("chunk length", String.valueOf(expectedLengths[i]),
                    r.header("Content-Length"));
            assertEquals("the whole-file SHA-256 must be on the wire", sha256Hex(big),
                    r.header("X-File-SHA256"));
        }

        byte[] received = new byte[BIG_SIZE];
        int off = 0;
        for (StubGateway.Request r : uploads) {
            System.arraycopy(r.body, 0, received, off, r.body.length);
            off += r.body.length;
        }
        assertEquals("the stub must have received every byte", BIG_SIZE, off);
        assertArrayEquals("concatenated chunks differ from the picked document", big, received);

        assertTrue("the status line never showed chunk progress, saw:\n" + seen,
                anyContains(seen, "1/3 chunks"));
        assertTrue("the status line never showed the last chunk, saw:\n" + seen,
                anyContains(seen, "3/3 chunks"));
        assertTrue("the status line never reported success, saw:\n" + seen,
                anyContains(seen, "uploaded " + BIG_NAME));
        // Evidence for the record: the status lines this run actually rendered.
        System.out.println("[FilesActivityTest] status texts during the chunked upload: " + seen);

        controller.pause().stop().destroy();
    }

    /**
     * A chunk rejected in the middle must be a failure that names the chunk and reports how far
     * the upload got — and the chunks after it must never be sent.
     */
    @Test
    public void aFailedMiddleChunkIsReportedWithPartialProgress() {
        installPickedDocument(BIG_NAME, patternedBytes(BIG_SIZE));
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (req.path.startsWith("/api/")) {
                return StubGateway.rpcOk(req, Json.obj());
            }
            if (req.path.startsWith("/fs/list")) {
                return StubGateway.Response.json(listBody());
            }
            if (req.path.startsWith("/fs/upload")) {
                int index = Integer.parseInt(req.header("X-Chunk-Index"));
                if (index == 1) {
                    return contractError(403, "Forbidden", "path_denied", "路径越界");
                }
                Map<String, Object> body = Json.obj();
                body.put("ok", true);
                body.put("completed", false);
                body.put("receivedChunks", index + 1);
                body.put("totalChunks", BIG_CHUNKS);
                return StubGateway.Response.json(body);
            }
            return StubGateway.Response.status(404, "unexpected " + req.target);
        });
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class).setup();
        FilesActivity activity = controller.get();
        assertTrue(waitForRequests(gw, 1));
        assertTrue(waitFor(() -> allText(activity).contains("4 entries"), 20_000L, "entries"));

        findButton(activity, "Upload").performClick();
        Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        Intent result = new Intent();
        result.setData(PICKED);
        Shadows.shadowOf(activity).receiveResult(request, Activity.RESULT_OK, result);

        assertTrue("expected an upload failure, text was:\n" + allText(activity),
                waitFor(() -> allText(activity).contains("upload failed"), 60_000L, "failure"));

        String text = allText(activity);
        assertTrue("the failing chunk must be named, text was:\n" + text,
                text.contains("第 2/3 块"));
        assertTrue("partial progress must be surfaced, text was:\n" + text,
                text.contains("已发送 1/3 块"));
        assertFalse("a failed upload must not be reported as uploaded, text was:\n" + text,
                text.contains("uploaded " + BIG_NAME));
        assertEquals("the chunk after the failing one must never be sent", 2,
                gw.requestsFor("/fs/upload").size());
        assertFalse(activity.isFinishing());

        controller.pause().stop().destroy();
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    public void fullLifecycleChainDoesNotThrow() {
        gw.setHandler(this::defaultRouter);
        storeLogin(gw.baseUrl(), "admin", "tok");

        ActivityController<FilesActivity> controller =
                Robolectric.buildActivity(FilesActivity.class);
        Activity a = controller.get();
        controller.create();
        controller.start();
        controller.resume();
        assertTrue("onCreate should trigger /fs/list", waitForRequests(gw, 1));
        controller.pause();
        controller.stop();
        controller.restart();
        controller.resume();
        controller.pause();
        controller.stop();
        controller.destroy();
        // ActivityController.destroy() runs onDestroy() but does not set the finishing flag,
        // so assert on the observable consequence instead: the Activity is torn down.
        assertFalse("the Activity should no longer be resumed", a.hasWindowFocus());
        assertNotNull(a.getWindow().getDecorView());
    }

    // ------------------------------------------------------------------ fixtures

    private StubGateway.Response defaultRouter(StubGateway.Exchange ex) {
        StubGateway.Request req = ex.req();
        if (req.path.startsWith("/api/")) {
            return StubGateway.rpcOk(req, Json.obj());
        }
        if (req.path.startsWith("/fs/list")) {
            // A different listing per directory, so navigation is observable.
            return StubGateway.Response.json(
                    req.param("path").isEmpty() ? listBody() : docsBody());
        }
        if (req.path.startsWith("/fs/download")) {
            return StubGateway.Response.bytes("hello-download".getBytes(
                    java.nio.charset.Charset.forName("UTF-8")), "application/octet-stream");
        }
        if (req.path.startsWith("/fs/upload")) {
            String index = req.header("X-Chunk-Index");
            if (index != null) {
                // Mode B: completed only on the last chunk, exactly as the contract says.
                int i = Integer.parseInt(index);
                int total = Integer.parseInt(req.header("X-Total-Chunks"));
                Map<String, Object> body = Json.obj();
                body.put("ok", true);
                body.put("completed", i == total - 1);
                body.put("receivedChunks", i + 1);
                body.put("totalChunks", total);
                return StubGateway.Response.json(body);
            }
            return StubGateway.Response.json(singleEntry("ok", Boolean.TRUE));
        }
        if (req.path.equals("/gateway/logout")) {
            return StubGateway.Response.json(singleEntry("ok", Boolean.TRUE));
        }
        return StubGateway.Response.status(404, "unexpected " + req.target);
    }

    /**
     * A contract-shaped error body ({@code {"ok":false,"error":…,"message":…}}) with a non-2xx
     * status; {@code Response.status()} would put {@code http_403} in {@code error}, which is
     * not what the gateway sends.
     */
    private static StubGateway.Response contractError(int status, String reason,
                                                      String error, String message) {
        Map<String, Object> body = Json.obj();
        body.put("ok", false);
        body.put("error", error);
        body.put("message", message);
        StubGateway.Response r = StubGateway.Response.json(body);
        r.status = status;
        r.reason = reason;
        return r;
    }

    /**
     * Records every text any {@link TextView} in the Activity ever displays, by attaching a
     * {@link TextWatcher} to each. The status line is overwritten as an upload progresses, so
     * this is the only way to assert that intermediate progress was actually rendered.
     */
    private static List<String> watchAllText(Activity activity) {
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        for (TextView tv : textViews(activity)) {
            seen.add(tv.getText() == null ? "" : tv.getText().toString());
            tv.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    // not needed
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // not needed
                }

                @Override
                public void afterTextChanged(Editable s) {
                    seen.add(s == null ? "" : s.toString());
                }
            });
        }
        return seen;
    }

    private static boolean anyContains(List<String> texts, String needle) {
        synchronized (texts) {
            for (String t : texts) {
                if (t != null && t.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Deterministic pseudo-random bytes: a swapped or truncated chunk cannot go unnoticed. */
    private static byte[] patternedBytes(int size) {
        byte[] b = new byte[size];
        new Random(20260929L).nextBytes(b);
        return b;
    }

    /**
     * Makes the picker return a document with this name and these bytes. Both halves matter:
     * the provider answers the {@code DISPLAY_NAME} query, while the bytes come from the
     * stream registered with {@code ShadowContentResolver} — registering only one of them
     * silently keeps serving the previous fixture.
     */
    private void installPickedDocument(String name, byte[] bytes) {
        TestFileProvider.installBytes(name, bytes);
        Shadows.shadowOf(appContext().getContentResolver())
                .registerInputStream(PICKED, new ByteArrayInputStream(bytes));
    }

    /** Independent SHA-256 (not {@code Http.sha256Hex}), so the assertion is not circular. */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }

    private static Map<String, Object> listBody() {
        List<Object> entries = new ArrayList<Object>();
        entries.add(entry("src", "src", "directory", 0));
        entries.add(entry("a.txt", "a.txt", "file", 1400));
        entries.add(entry("docs", "docs", "directory", 0));
        entries.add(entry("z.bin", "z.bin", "file", 2_500_000));
        Map<String, Object> body = Json.obj();
        body.put("ok", true);
        body.put("entries", entries);
        return body;
    }

    /** The contents of "docs" — a different listing so navigation is observable. */
    private static Map<String, Object> docsBody() {
        List<Object> entries = new ArrayList<Object>();
        entries.add(entry("guide.md", "docs/guide.md", "file", 2048));
        entries.add(entry("notes.txt", "docs/notes.txt", "file", 64));
        Map<String, Object> body = Json.obj();
        body.put("ok", true);
        body.put("entries", entries);
        return body;
    }

    private static Map<String, Object> entry(String name, String path, String type, long size) {
        Map<String, Object> m = Json.obj();
        m.put("name", name);
        m.put("path", path);
        m.put("type", type);
        m.put("size", size);
        return m;
    }

    private static Map<String, Object> singleEntry(String k, Object v) {
        Map<String, Object> m = Json.obj();
        m.put(k, v);
        return m;
    }

    private static String readFile(File f) {
        try {
            InputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ListView findListView(Activity activity) {
        List<ListView> found = new ArrayList<ListView>();
        collectViews(activity.getWindow().getDecorView(), ListView.class, found);
        assertFalse("no ListView in the Activity", found.isEmpty());
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collectViews(View root, Class<T> type, List<T> out) {
        if (root == null) {
            return;
        }
        if (type.isInstance(root)) {
            out.add((T) root);
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectViews(g.getChildAt(i), type, out);
            }
        }
    }

    private static List<String> renderedRowText(ListView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 1920);
        BaseAdapter adapter = (BaseAdapter) list.getAdapter();
        List<String> rows = new ArrayList<String>();
        for (int i = 0; i < adapter.getCount(); i++) {
            View row = adapter.getView(i, null, list);
            row.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
            rows.add(textOf(row).trim());
        }
        return rows;
    }

    private static String textOf(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof android.widget.TextView) {
            sb.append(((android.widget.TextView) v).getText());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(' ').append(textOf(g.getChildAt(i)));
            }
        }
        return sb.toString();
    }

    /**
     * Stands in for the Storage Access Framework's document provider: it answers the
     * {@code OpenableColumns.DISPLAY_NAME} query and serves the picked file's bytes, which is
     * exactly the surface {@link FilesActivity} uses.
     */
    public static final class TestFileProvider extends ContentProvider {

        private static byte[] payload = new byte[0];
        private static String displayName = PICKED_NAME;

        static void install(String body) {
            installBytes(PICKED_NAME,
                    body.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        }

        /** Same, but with an arbitrary name and byte payload (used by the mode-B tests). */
        static void installBytes(String name, byte[] bytes) {
            payload = bytes;
            displayName = name;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            MatrixCursor c = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME});
            c.addRow(new Object[] {displayName});
            return c;
        }

        /** {@code ContentProvider} has no overridable {@code openInputStream} in API 35. */
        public InputStream openInputStream(Uri uri, String mode) {
            return new ByteArrayInputStream(payload);
        }

        @Override
        public String getType(Uri uri) {
            return "text/plain";
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 0;
        }

        /** Writes the payload into a real file (kept for tests that need a File, not a stream). */
        static File writeTo(File dir) throws Exception {
            File f = new File(dir, PICKED_NAME);
            OutputStream out = new FileOutputStream(f);
            out.write(payload);
            out.close();
            return f;
        }
    }

    /**
     * Stands in for the system MediaStore provider (authority {@code media}).
     *
     * <p>Robolectric has no MediaStore: {@code PackageManager.resolveContentProvider("media")}
     * finds nothing, so {@code ContentResolver.insert} on
     * {@code MediaStore.Downloads.EXTERNAL_CONTENT_URI} would otherwise return a synthetic URI
     * and {@code openOutputStream} would throw. This provider is registered for the authority
     * the production code actually uses (the same technique the SAF picker double above uses)
     * and records exactly what the app writes: the inserted/updated columns, the bytes that went
     * through the row's output stream, and how many rows were inserted and deleted.</p>
     *
     * <p>It is a test double. It proves the app's calls and their arguments, not that the real
     * MediaStore would accept them.</p>
     */
    public static final class TestMediaStoreProvider extends ContentProvider {

        static final String AUTHORITY = "media";

        static final class Row {
            final Uri uri;
            final ContentValues values;
            final File file;
            final int pendingAtInsert;

            Row(Uri uri, ContentValues values, File file, int pendingAtInsert) {
                this.uri = uri;
                this.values = values;
                this.file = file;
                this.pendingAtInsert = pendingAtInsert;
            }

            int pending() {
                Integer p = values.getAsInteger(MediaStore.Downloads.IS_PENDING);
                return p == null ? -1 : p.intValue();
            }
        }

        private final Context context;
        private final File dir;
        private final Map<String, Row> rows = new LinkedHashMap<String, Row>();
        private int nextId = 0;
        private int inserts = 0;
        private int deletes = 0;

        TestMediaStoreProvider(Context context, File dir) {
            this.context = context;
            this.dir = dir;
        }

        int insertCount() {
            synchronized (rows) {
                return inserts;
            }
        }

        int deleteCount() {
            synchronized (rows) {
                return deletes;
            }
        }

        List<Row> rows() {
            synchronized (rows) {
                return new ArrayList<Row>(rows.values());
            }
        }

        List<Row> publishedRows() {
            List<Row> out = new ArrayList<Row>();
            for (Row r : rows()) {
                if (r.pending() == 0) {
                    out.add(r);
                }
            }
            return out;
        }

        byte[] bytesOf(Row row) throws IOException {
            InputStream in = new FileInputStream(row.file);
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            final Uri row;
            final File backing;
            synchronized (rows) {
                int id = ++nextId;
                row = Uri.parse(uri.toString() + "/" + id);
                backing = new File(dir, "media-" + id + ".bin");
                ContentValues copy = (values == null)
                        ? new ContentValues() : new ContentValues(values);
                Integer pending = copy.getAsInteger(MediaStore.Downloads.IS_PENDING);
                rows.put(row.toString(),
                        new Row(row, copy, backing, pending == null ? -1 : pending.intValue()));
                inserts++;
            }
            // Robolectric shadows ContentResolver.openOutputStream: it answers from a registered
            // stream map before any provider is consulted, so the row's backing file is
            // registered here. A supplier is used so every open gets a fresh stream.
            Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(row, () -> {
                try {
                    return new FileOutputStream(backing);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            return row;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            synchronized (rows) {
                Row r = rows.get(uri.toString());
                if (r == null) {
                    return 0;
                }
                if (values != null) {
                    r.values.putAll(values);
                }
                return 1;
            }
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            synchronized (rows) {
                deletes++;
                Row removed = rows.remove(uri.toString());
                if (removed != null) {
                    removed.file.delete();
                }
                return removed == null ? 0 : 1;
            }
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            MatrixCursor c = new MatrixCursor(new String[] {
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.MIME_TYPE,
                    MediaStore.Downloads.IS_PENDING});
            synchronized (rows) {
                Row r = rows.get(uri.toString());
                if (r != null) {
                    c.addRow(new Object[] {
                            r.values.getAsString(MediaStore.Downloads.DISPLAY_NAME),
                            r.values.getAsString(MediaStore.Downloads.MIME_TYPE),
                            r.values.getAsInteger(MediaStore.Downloads.IS_PENDING)});
                }
            }
            return c;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }
    }
}
