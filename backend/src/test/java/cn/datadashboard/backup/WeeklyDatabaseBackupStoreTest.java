package cn.datadashboard.backup;

import cn.datadashboard.api.DatabaseBackupController;
import cn.datadashboard.security.TrialRequestGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyDatabaseBackupStoreTest {
    @TempDir Path dir;
    static final String FIRST = "a".repeat(32), SECOND = "b".repeat(32);
    final JsonMapper json = JsonMapper.builder().build();
    Path root() { return dir.resolve("backup"); }
    WeeklyDatabaseBackupStore store() { return new WeeklyDatabaseBackupStore(root()); }
    Path sql(String generation) { return root().resolve("generations").resolve(generation).resolve("business.sql"); }
    @Test void compressedDownloadPreservesGzipBytesAndFilename() throws Exception {
        byte[] original="synthetic SQL".getBytes();
        var buffer=new ByteArrayOutputStream();
        try(var gzip=new java.util.zip.GZIPOutputStream(buffer)){gzip.write(original);}
        byte[] compressed=buffer.toByteArray();
        var metadata=manifest(FIRST,compressed);
        metadata.put("schemaVersion",2);metadata.put("compression","gzip");metadata.put("sqlBytes",original.length);
        metadata.put("sqlSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
        Files.createDirectories(sql(FIRST).getParent());Files.write(sql(FIRST).resolveSibling("business.sql.gz"),compressed);
        Files.writeString(root().resolve("current.json"),json.writeValueAsString(metadata));
        assertEquals("gzip",store().status().latest().compression());
        try(var download=store().openDownload(FIRST)){
            assertTrue(download.filename().endsWith(".sql.gz"));
            var output=new ByteArrayOutputStream();download.writeTo(output);assertArrayEquals(compressed,output.toByteArray());
        }
    }
    Map<String, Object> manifest(String generation, byte[] content) throws Exception {
        return new HashMap<>(Map.of("schemaVersion", 1, "generation", generation, "database", "SCSJ_LOCAL_CLOUD",
                "createdAt", "2026-10-18T15:00:00Z", "bytes", content.length,
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                "validation", "DUMP_EXIT_AND_HASH_READBACK", "restoreTested", false));
    }
    void publish(String generation, byte[] content) throws Exception {
        Files.createDirectories(sql(generation).getParent()); Files.write(sql(generation), content);
        Files.writeString(root().resolve("current.json"), json.writeValueAsString(manifest(generation, content)));
    }
    void attempt(String status) throws Exception {
        var node = new HashMap<String, Object>();
        node.put("schemaVersion", 1); node.put("status", status); node.put("startedAt", "2026-10-18T15:00:00Z");
        node.put("completedAt", status.equals("RUNNING") ? null : "2026-10-18T15:01:00Z");
        node.put("errorCode", status.equals("FAILED") ? "DUMP_FAILED" : null);
        Files.createDirectories(root()); Files.writeString(root().resolve("last-attempt.json"), json.writeValueAsString(node));
    }
    @Test void newTargetDatabaseDownloadAndUnknownDatabaseRejection() throws Exception {
        byte[] data = "synthetic target backup".getBytes();
        publish(FIRST, data);
        var node = manifest(FIRST, data); node.put("database", "material_dashboard");
        Files.writeString(root().resolve("current.json"), json.writeValueAsString(node));
        assertTrue(store().status().available());
        try (var download = store().openDownload(FIRST)) {
            var output = new ByteArrayOutputStream(); download.writeTo(output);
            assertArrayEquals(data, output.toByteArray());
        }
        node.put("database", "unrelated_database");
        Files.writeString(root().resolve("current.json"), json.writeValueAsString(node));
        assertThrows(IOException.class, () -> store().status());
    }
    @Test void noBackupDoesNotCreateStorageAndPartialGenerationIsNotPublished() throws Exception {
        assertFalse(store().status().available()); assertFalse(Files.exists(root()));
        Files.createDirectories(sql(FIRST).getParent()); Files.writeString(sql(FIRST), "incomplete"); attempt("RUNNING");
        var status = store().status(); assertFalse(status.available()); assertNull(status.latest());
        assertEquals("RUNNING", status.lastAttempt().status()); assertEquals(1, status.retention());
        assertEquals("每周日23:00（上海时间）", status.schedule());
    }
    @Test void statusDoesNotHashSqlButDownloadRejectsSameSizeCorruptionAndReleasesSlot() throws Exception {
        publish(FIRST, "original".getBytes()); var store = store();
        Files.writeString(sql(FIRST), "corrupt!");
        assertTrue(store.status().available());
        assertThrows(IOException.class, () -> store.openDownload(FIRST));
        publish(FIRST, "original".getBytes());
        try (var download = store.openDownload(FIRST)) { assertEquals(8, download.latest().bytes()); }
    }
    @Test void missingAndSizeChangedCurrentFailClosed() throws Exception {
        publish(FIRST, new byte[]{1, 2, 3}); Files.write(sql(FIRST), new byte[]{1});
        assertThrows(IOException.class, () -> store().status());
        Files.delete(sql(FIRST)); assertThrows(IOException.class, () -> store().status());
    }
    @Test void malformedManifestMetadataAndUnsafeGenerationAreRejected() throws Exception {
        publish(FIRST, new byte[]{1}); var valid = manifest(FIRST, new byte[]{1});
        for (var invalid : List.of(Map.entry("generation", "../private"), Map.entry("database", "other"),
                Map.entry("sha256", "X".repeat(64)), Map.entry("createdAt", "not-a-date"),
                Map.entry("validation", "RESTORE_PASSED"), Map.entry("restoreTested", true),
                Map.entry("schemaVersion", 2), Map.entry("bytes", 0), Map.entry("bytes", 1.5))) {
            var node = new HashMap<>(valid); node.put(invalid.getKey(), invalid.getValue());
            Files.writeString(root().resolve("current.json"), json.writeValueAsString(node));
            assertThrows(IOException.class, () -> store().status(), invalid.getKey());
        }
        String document = json.writeValueAsString(valid);
        for (String invalid : List.of("not-json", "[]", " ".repeat(9000), document + " {}",
                document.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))) {
            Files.writeString(root().resolve("current.json"), invalid);
            assertThrows(IOException.class, () -> store().status());
        }
        assertThrows(IllegalArgumentException.class, () -> store().openDownload("../private"));
    }
    @Test void failedAttemptStillOffersLastGoodBackupAndDoesNotExposePrivateFields() throws Exception {
        publish(FIRST, new byte[]{1}); attempt("FAILED");
        var status = store().status(); assertTrue(status.available()); assertEquals("FAILED", status.lastAttempt().status());
        assertEquals("DUMP_FAILED", status.lastAttempt().errorCode());
        String encoded = json.writeValueAsString(status);
        assertFalse(encoded.contains(root().toString())); assertFalse(encoded.contains("SCSJ_LOCAL_CLOUD"));
        assertFalse(encoded.contains("business.sql")); assertFalse(status.latest().restoreTested());
    }
    @Test void invalidAttemptCannotInjectMessagesOrPrivatePaths() throws Exception {
        publish(FIRST, new byte[]{1}); attempt("FAILED");
        String original = Files.readString(root().resolve("last-attempt.json"));
        Files.writeString(root().resolve("last-attempt.json"), original.replace("DUMP_FAILED", "/private/password"));
        assertThrows(IOException.class, () -> store().status());
    }
    @Test void staleDownloadDoesNotSilentlySubstituteNewBackup() throws Exception {
        publish(FIRST, new byte[]{1}); publish(SECOND, new byte[]{2});
        var failure = assertThrows(WeeklyDatabaseBackupStore.Conflict.class, () -> store().openDownload(FIRST));
        assertEquals("BACKUP_GENERATION_CHANGED", failure.getMessage());
    }
    @Test void openedDownloadRemainsPinnedWhenPublishedGenerationChanges() throws Exception {
        byte[] first = new byte[256 * 1024 + 17]; new Random(7).nextBytes(first); publish(FIRST, first);
        var store = store();
        try (var download = store.openDownload(FIRST)) {
            publish(SECOND, new byte[]{2});
            // Windows cannot always unlink an opened file; Linux's worker can, but the handle stays pinned.
            if (!System.getProperty("os.name").startsWith("Windows")) Files.delete(sql(FIRST));
            var output = new ByteArrayOutputStream(); download.writeTo(output);
            assertArrayEquals(first, output.toByteArray()); assertEquals(FIRST, download.latest().generation());
            assertEquals(SECOND, store.status().latest().generation());
            assertEquals("scsj-database-20261018T150000Z.sql", download.filename());
        }
    }
    @Test void downloadStreamsBoundedChunksAndOnlyOneDownloadRunsAtOnce() throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024 + 3]; publish(FIRST, bytes); var store = store();
        try (var download = store.openDownload(FIRST)) {
            var failure = assertThrows(WeeklyDatabaseBackupStore.Conflict.class, () -> store.openDownload(FIRST));
            assertEquals("BACKUP_DOWNLOAD_BUSY", failure.getMessage());
            var count = new java.util.concurrent.atomic.AtomicLong();
            download.writeTo(new java.io.OutputStream() {
                public void write(int b) { fail("Expected bounded block transfer"); }
                public void write(byte[] block, int offset, int length) {
                    assertTrue(length <= 64 * 1024); count.addAndGet(length);
                }
            });
            assertEquals(bytes.length, count.get());
        }
        try (var next = store.openDownload(FIRST)) { assertEquals(bytes.length, next.latest().bytes()); }
    }
    @Test void disconnectedDownloadReleasesTheSingleDownloadSlot() throws Exception {
        publish(FIRST, new byte[]{1, 2, 3}); var store = store();
        assertThrows(IOException.class, () -> {
            try (var download = store.openDownload(FIRST)) {
                download.writeTo(new java.io.OutputStream() {
                    public void write(int b) throws IOException { throw new IOException("synthetic disconnect"); }
                    public void write(byte[] b, int offset, int length) throws IOException { throw new IOException("synthetic disconnect"); }
                });
            }
        });
        try (var next = store.openDownload(FIRST)) { assertEquals(3, next.latest().bytes()); }
    }
    @Test void symlinkSqlManifestAndParentAreRejectedWhereSupported() throws Exception {
        Path outside = dir.resolve("outside.sql"); Files.writeString(outside, "a");
        try { Files.createSymbolicLink(dir.resolve("probe-link"), outside); }
        catch (UnsupportedOperationException | IOException permission) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links unavailable to test process");
        }
        publish(FIRST, new byte[]{'a'}); Files.delete(sql(FIRST)); Files.createSymbolicLink(sql(FIRST), outside);
        assertThrows(IOException.class, () -> store().status()); assertThrows(IOException.class, () -> store().openDownload(FIRST));
        Files.delete(sql(FIRST)); Files.write(sql(FIRST), new byte[]{'a'});
        Path actual = dir.resolve("manifest.json"); Files.move(root().resolve("current.json"), actual);
        Files.createSymbolicLink(root().resolve("current.json"), actual); assertThrows(IOException.class, () -> store().status());
        Files.delete(root().resolve("current.json")); Files.move(actual, root().resolve("current.json"));
        Path generation = sql(FIRST).getParent(), moved = dir.resolve("moved-generation");
        Files.move(generation, moved); Files.createSymbolicLink(generation, moved);
        assertThrows(IOException.class, () -> store().status());
    }
    @Test void controllerRequiresAuthenticatedOperationsAndSafeOriginForBothReads() throws Exception {
        publish(FIRST, new byte[]{1, 2, 3});
        var controller = new DatabaseBackupController(store(), new TrialRequestGuard("https://example.test"));
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        assertThrows(SecurityException.class, () -> controller.status(request, response));
        assertThrows(SecurityException.class, () -> controller.download(FIRST, request, response));
        request.setUserPrincipal(() -> "viewer"); request.addUserRole("VIEWER");
        assertThrows(SecurityException.class, () -> controller.status(request, response));
        request.addUserRole("OPERATIONS"); assertTrue(controller.status(request, response).available());
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThrows(SecurityException.class, () -> controller.download(FIRST, request, response));
        request.removeHeader("Sec-Fetch-Site"); request.addHeader("Origin", "https://evil.test");
        assertThrows(SecurityException.class, () -> controller.status(request, response));
        request.removeHeader("Origin"); request.setUserPrincipal(() -> "admin"); request.addUserRole("ADMIN");
        controller.download(FIRST, request, response);
        assertArrayEquals(new byte[]{1, 2, 3}, response.getContentAsByteArray());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering")); assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    }
}
