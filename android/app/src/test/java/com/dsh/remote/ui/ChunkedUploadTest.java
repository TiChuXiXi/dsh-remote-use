package com.dsh.remote.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dsh.remote.net.GatewayClient;
import com.dsh.remote.net.GatewayException;
import com.dsh.remote.net.Http;
import com.dsh.remote.net.Json;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The chunked upload (mode B, docs/GATEWAY-API.md §2.3) at the wire level.
 *
 * <p>These tests drive the <b>real</b> {@code net/} stack — {@link GatewayClient} →
 * {@link Http} → {@code HttpURLConnection} → a real loopback socket — against
 * {@link StubGateway}. Nothing in {@code java/com/dsh/remote/} is mocked: the assertions are
 * made on the bytes and headers the stub actually read off the socket.</p>
 *
 * <p>No Robolectric is needed here: nothing in this path touches Android. The UI-level mode-B
 * behaviour (threshold, status line) lives in {@code FilesActivityTest}.</p>
 */
public class ChunkedUploadTest {

    private static final int CHUNK = GatewayClient.CHUNK_SIZE_BYTES;
    private static final String REL_PATH = "docs/big file.bin";
    private static final String FILE_NAME = "big file.bin";

    private StubGateway gw;
    private File dir;

    @Before
    public void setUp() throws Exception {
        gw = new StubGateway();
        dir = java.nio.file.Files.createTempDirectory("chunked-upload").toFile();
    }

    @After
    public void tearDown() {
        if (gw != null) {
            gw.close();
        }
        if (dir != null) {
            deleteRecursively(dir);
        }
    }

    // ------------------------------------------------------------------ happy path

    /**
     * The full contract: one request per chunk, a shared {@code X-Upload-Id}, a 0-based strictly
     * increasing {@code X-Chunk-Index}, the right {@code X-Total-Chunks}, a correct whole-file
     * {@code X-File-SHA256}, and — the assertion that matters most — the concatenation of the
     * received chunk bodies being byte-identical to the source file.
     */
    @Test
    public void chunkedUploadSendsTheContractedHeadersAndByteIdenticalChunks() throws Exception {
        final int totalChunks = 3;
        final byte[] source = randomBytes(2L * CHUNK + 7);   // two full chunks + a 7-byte tail
        final File src = writeFile(FILE_NAME, source);
        final String expectedSha = sha256Hex(source);

        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            if (!"/fs/upload".equals(req.path)) {
                return StubGateway.Response.status(404, "unexpected " + req.target);
            }
            int index = Integer.parseInt(req.header("X-Chunk-Index"));
            int total = Integer.parseInt(req.header("X-Total-Chunks"));
            Map<String, Object> body = Json.obj();
            body.put("ok", true);
            body.put("completed", index == total - 1);
            body.put("receivedChunks", index + 1);
            body.put("totalChunks", total);
            if (index == total - 1) {
                body.put("path", REL_PATH);
                body.put("size", source.length);
                body.put("sha256", expectedSha);
            }
            return StubGateway.Response.json(body);
        });

        GatewayClient client = new GatewayClient(gw.baseUrl());
        client.http().setBearer("tok");
        final List<String> progress = new ArrayList<String>();
        GatewayClient.ChunkedUploadResult result = client.uploadChunked(REL_PATH, src, FILE_NAME,
                new GatewayClient.UploadProgress() {
                    @Override
                    public void chunkSent(int chunkIndex, int total, long sent, long all) {
                        progress.add(chunkIndex + "/" + total + " " + sent + "/" + all);
                    }
                });

        List<StubGateway.Request> uploads = gw.requestsFor("/fs/upload");
        assertEquals("one request per chunk", totalChunks, uploads.size());

        String uploadId = uploads.get(0).header("X-Upload-Id");
        assertNotNull("X-Upload-Id is required", uploadId);
        assertTrue("X-Upload-Id must match the gateway's [A-Za-z0-9_-]{1,128}, got: " + uploadId,
                uploadId.matches("[A-Za-z0-9_-]{1,128}"));

        long[] expectedChunkBytes = new long[] {CHUNK, CHUNK, 7};
        for (int i = 0; i < totalChunks; i++) {
            StubGateway.Request r = uploads.get(i);
            String where = "chunk " + i + ": " + r.header("X-Chunk-Index") + "/"
                    + r.header("X-Total-Chunks");
            assertEquals("X-Chunk-Index must start at 0 and increase by one (" + where + ")",
                    String.valueOf(i), r.header("X-Chunk-Index"));
            assertEquals("X-Total-Chunks must be the same on every chunk (" + where + ")",
                    String.valueOf(totalChunks), r.header("X-Total-Chunks"));
            assertEquals("all chunks of one upload share X-Upload-Id (" + where + ")",
                    uploadId, r.header("X-Upload-Id"));
            assertEquals("X-File-Path must be the url-encoded target (" + where + ")",
                    Http.urlEncode(REL_PATH), r.header("X-File-Path"));
            assertEquals("X-File-Path must decode back to the real relative path (" + where + ")",
                    REL_PATH, URLDecoder.decode(r.header("X-File-Path"), "UTF-8"));
            assertEquals("X-File-Name (" + where + ")", FILE_NAME, r.header("X-File-Name"));
            assertEquals("mode B is application/octet-stream (" + where + ")",
                    "application/octet-stream", r.header("Content-Type"));
            assertEquals("Content-Length must be the chunk size (" + where + ")",
                    String.valueOf(expectedChunkBytes[i]), r.header("Content-Length"));
            assertEquals("the whole-file hash must be on every chunk (" + where + ")",
                    expectedSha, r.header("X-File-SHA256"));
            assertEquals("the bearer must be sent (" + where + ")",
                    "Bearer tok", r.header("Authorization"));
        }
        // The requirement is "send it on the first chunk" — assert that explicitly rather than
        // only as part of the loop above.
        assertEquals("the first chunk must carry the whole-file SHA-256",
                expectedSha, uploads.get(0).header("X-File-SHA256"));

        byte[] received = concatBodies(uploads);
        assertEquals("the stub must have received the whole file", source.length, received.length);
        assertArrayEquals("concatenated chunks differ from the source file", source, received);

        assertEquals("progress callbacks", Arrays.asList(
                "1/3 " + CHUNK + "/" + source.length,
                "2/3 " + (2L * CHUNK) + "/" + source.length,
                "3/3 " + source.length + "/" + source.length), progress);

        assertTrue("the server said completed=true", result.completed);
        assertEquals(totalChunks, result.totalChunks);
        assertEquals(totalChunks, result.receivedChunks);
        assertEquals(REL_PATH, result.path);
        assertEquals(source.length, result.size);
        assertEquals(expectedSha, result.sha256);

        // A real captured request, for the record (see the test's system-out).
        System.out.println("[ChunkedUploadTest] first chunk as it went over the wire:\n"
                + capture(uploads.get(0)));
    }

    /** An exact multiple of the chunk size must not produce a trailing empty chunk. */
    @Test
    public void anExactMultipleOfTheChunkSizeProducesNoTrailingEmptyChunk() throws Exception {
        final byte[] source = randomBytes(2L * CHUNK);
        final File src = writeFile("exact.bin", source);
        gw.setHandler(this::acceptEveryChunk);

        GatewayClient client = new GatewayClient(gw.baseUrl());
        GatewayClient.ChunkedUploadResult result =
                client.uploadChunked("exact.bin", src, "exact.bin");

        assertEquals("2 chunks of 4 MiB, no third empty one", 2, gw.requestsFor("/fs/upload").size());
        assertArrayEquals(source, concatBodies(gw.requestsFor("/fs/upload")));
        assertTrue(result.completed);
    }

    /** An empty file is still one (empty) chunk — the contract rejects total == 0. */
    @Test
    public void anEmptyFileIsSentAsASingleEmptyChunk() throws Exception {
        final File src = writeFile("empty.bin", new byte[0]);
        gw.setHandler(this::acceptEveryChunk);

        GatewayClient client = new GatewayClient(gw.baseUrl());
        GatewayClient.ChunkedUploadResult result =
                client.uploadChunked("empty.bin", src, "empty.bin");

        List<StubGateway.Request> uploads = gw.requestsFor("/fs/upload");
        assertEquals(1, uploads.size());
        assertEquals("0", uploads.get(0).header("X-Chunk-Index"));
        assertEquals("1", uploads.get(0).header("X-Total-Chunks"));
        assertEquals("0", uploads.get(0).header("Content-Length"));
        assertEquals(0, uploads.get(0).body.length);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                uploads.get(0).header("X-File-SHA256"));
        assertTrue(result.completed);
    }

    // ------------------------------------------------------------------ failures

    /**
     * A chunk rejected in the middle of the upload must abort the whole thing: the exception
     * names the chunk, the chunks after it are never sent, and no success is reported.
     */
    @Test
    public void failingMiddleChunkAbortsTheUploadAndIsReportedAsFailure() throws Exception {
        final byte[] source = randomBytes(2L * CHUNK + 7);
        final File src = writeFile(FILE_NAME, source);
        gw.setHandler(ex -> {
            StubGateway.Request req = ex.req();
            int index = Integer.parseInt(req.header("X-Chunk-Index"));
            if (index == 1) {
                return contractError(403, "Forbidden", "path_denied", "路径越界");
            }
            Map<String, Object> body = Json.obj();
            body.put("ok", true);
            body.put("completed", false);
            body.put("receivedChunks", index + 1);
            body.put("totalChunks", 3);
            return StubGateway.Response.json(body);
        });

        GatewayClient client = new GatewayClient(gw.baseUrl());
        final List<Integer> progress = new ArrayList<Integer>();
        try {
            client.uploadChunked(REL_PATH, src, FILE_NAME, new GatewayClient.UploadProgress() {
                @Override
                public void chunkSent(int chunkIndex, int total, long sent, long all) {
                    progress.add(chunkIndex);
                }
            });
            fail("a rejected chunk must not be reported as a successful upload");
        } catch (GatewayException e) {
            assertEquals("the gateway's status must survive", 403, e.status);
            assertEquals("path_denied", e.error);
            assertTrue("the error must name the failing chunk, got: " + e.getMessage(),
                    e.getMessage().contains("第 2/3 块"));
        }

        List<StubGateway.Request> uploads = gw.requestsFor("/fs/upload");
        assertEquals("the upload must stop at the first failing chunk", 2, uploads.size());
        assertEquals("0", uploads.get(0).header("X-Chunk-Index"));
        assertEquals("1", uploads.get(1).header("X-Chunk-Index"));
        assertEquals("only chunk 1 was accepted", Arrays.asList(1), progress);
    }

    /** A server that accepts every chunk but never confirms the merge must not be a success. */
    @Test
    public void aServerThatNeverReportsCompletedIsReportedAsFailure() throws Exception {
        final byte[] source = randomBytes(2L * CHUNK + 7);
        final File src = writeFile(FILE_NAME, source);
        gw.setHandler(ex -> {
            Map<String, Object> body = Json.obj();
            body.put("ok", true);
            body.put("completed", false);
            body.put("receivedChunks", 3);
            body.put("totalChunks", 3);
            return StubGateway.Response.json(body);
        });

        GatewayClient client = new GatewayClient(gw.baseUrl());
        try {
            GatewayClient.ChunkedUploadResult r = client.uploadChunked(REL_PATH, src, FILE_NAME);
            fail("completed was never true, so this must not return a result: " + r.completed);
        } catch (IOException e) {
            assertTrue("the error must say the merge was never confirmed, got: " + e.getMessage(),
                    e.getMessage().contains("completed=true"));
        }
        assertEquals("all chunks were sent before giving up", 3, gw.requestsFor("/fs/upload").size());
    }

    /** A 200 whose body is not the contract's JSON object is a failure, not a silent success. */
    @Test
    public void anUnparseableSuccessBodyIsReportedAsFailure() throws Exception {
        final File src = writeFile("junk.bin", randomBytes(16));
        gw.setHandler(ex -> StubGateway.Response.text(200, "OK", "not json at all", "text/plain"));

        GatewayClient client = new GatewayClient(gw.baseUrl());
        try {
            client.uploadChunked("junk.bin", src, "junk.bin");
            fail("an unparseable response must not be treated as a completed upload");
        } catch (IOException e) {
            assertTrue("the error must say the response was unparseable, got: " + e.getMessage(),
                    e.getMessage().contains("unparseable"));
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Answers mode B properly (completed only on the last chunk) for any chunk count. */
    private StubGateway.Response acceptEveryChunk(StubGateway.Exchange ex) {
        StubGateway.Request req = ex.req();
        if (!"/fs/upload".equals(req.path)) {
            return StubGateway.Response.status(404, "unexpected " + req.target);
        }
        int index = Integer.parseInt(req.header("X-Chunk-Index"));
        int total = Integer.parseInt(req.header("X-Total-Chunks"));
        Map<String, Object> body = Json.obj();
        body.put("ok", true);
        body.put("completed", index == total - 1);
        body.put("receivedChunks", index + 1);
        body.put("totalChunks", total);
        if (index == total - 1) {
            body.put("path", req.header("X-File-Name"));
            body.put("size", -1);
            body.put("sha256", req.header("X-File-SHA256"));
        }
        return StubGateway.Response.json(body);
    }

    /**
     * A contract-shaped error body ({@code {"ok":false,"error":…,"message":…}}) with a non-2xx
     * status — {@code Response.status()} would put {@code http_403} in {@code error}, which is
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

    private File writeFile(String name, byte[] bytes) throws IOException {
        File f = new File(dir, name);
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        return f;
    }

    /** Deterministic pseudo-random bytes, so a swapped/duplicated chunk cannot go unnoticed. */
    private static byte[] randomBytes(long size) {
        byte[] b = new byte[(int) size];
        new Random(20260929L).nextBytes(b);
        return b;
    }

    private byte[] concatBodies(List<StubGateway.Request> uploads) {
        int total = 0;
        for (StubGateway.Request r : uploads) {
            total += r.body.length;
        }
        byte[] out = new byte[total];
        int off = 0;
        for (StubGateway.Request r : uploads) {
            System.arraycopy(r.body, 0, out, off, r.body.length);
            off += r.body.length;
        }
        return out;
    }

    /** Independent SHA-256 (not {@code Http.sha256Hex}) so the assertion is not circular. */
    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** The request line + header block, in the order the stub read it off the socket. */
    private static String capture(StubGateway.Request req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.method).append(' ').append(req.target).append(" HTTP/1.1\r\n");
        Map<String, String> ordered = new LinkedHashMap<String, String>(req.headers);
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("\r\n<").append(req.body.length).append(" body bytes>");
        return sb.toString();
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
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
