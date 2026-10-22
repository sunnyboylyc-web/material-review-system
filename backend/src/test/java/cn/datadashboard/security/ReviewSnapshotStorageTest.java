package cn.datadashboard.security;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReviewSnapshotStorageTest {
    @TempDir Path root;
    final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();

    private byte[] gzip(byte[] value) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(value); }
        return bytes.toByteArray();
    }

    private void onlyTargetRemains(Path target) throws IOException {
        try (var files = Files.list(root)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void smallPlainLargeCompressedAndSmallAgainShareOneAtomicPath() throws Exception {
        var storage = new ReviewSnapshotStorage(512, 8192);
        Path target = root.resolve("saved.json");
        var small = Map.of("version", 1, "result", "small");
        storage.write(target, small);
        assertEquals('{', Files.readAllBytes(target)[0]);
        assertEquals(small, storage.read(target, Map.class));
        var large = Map.of("version", 1, "result", "完整保留明细".repeat(200));
        storage.write(target, large);
        byte[] encoded = Files.readAllBytes(target);
        assertEquals(0x1f, encoded[0] & 0xff); assertEquals(0x8b, encoded[1] & 0xff);
        assertTrue(encoded.length <= 512);
        assertEquals(large, storage.read(target, Map.class));
        storage.write(target, small);
        assertEquals('{', Files.readAllBytes(target)[0]);
        assertEquals(small, storage.read(target, Map.class));
        onlyTargetRemains(target);
    }

    @Test void decodedAndCompressedLimitsRetainPreviousBytesAndRemoveTemporaryFiles() throws Exception {
        Path target = root.resolve("saved.json");
        var storage = new ReviewSnapshotStorage(256, 4096);
        storage.write(target, Map.of("result", "previous"));
        byte[] old = Files.readAllBytes(target);
        var decodedTooLarge = Map.of("result", "x".repeat(5000));
        Exception first = assertThrows(Exception.class, () -> storage.write(target, decodedTooLarge));
        assertTrue(ReviewSnapshotStorage.isSizeLimit(first));
        assertArrayEquals(old, Files.readAllBytes(target));
        byte[] random = new byte[1400]; new Random(209).nextBytes(random);
        var compressedTooLarge = Map.of("result", Base64.getEncoder().encodeToString(random));
        Exception second = assertThrows(Exception.class, () -> storage.write(target, compressedTooLarge));
        assertTrue(ReviewSnapshotStorage.isSizeLimit(second));
        assertArrayEquals(old, Files.readAllBytes(target));
        onlyTargetRemains(target);
    }

    @Test void decodedReadLimitRejectsHighlyCompressedInput() throws Exception {
        Path target = root.resolve("saved.json");
        Files.write(target, gzip(json.writeValueAsBytes(Map.of("value", "x".repeat(20000)))));
        assertTrue(Files.size(target) < 256);
        var failure = assertThrows(Exception.class, () -> new ReviewSnapshotStorage(256, 4096).read(target, Map.class));
        assertTrue(ReviewSnapshotStorage.isSizeLimit(failure));
    }

    @Test void trailingJsonTruncatedGzipBadCrcAndOversizedFileAreRejected() throws Exception {
        Path target = root.resolve("saved.json");
        var storage = new ReviewSnapshotStorage(512, 4096);
        byte[] payload = json.writeValueAsBytes(Map.of("value", "x".repeat(1000)));
        byte[] compressed = gzip(payload);
        byte[] badCrc = compressed.clone(); badCrc[badCrc.length - 8] ^= 1;
        for (byte[] invalid : List.of(
                "{\"value\":1}{\"value\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                gzip("{\"value\":1}{\"value\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Arrays.copyOf(compressed, compressed.length - 3), badCrc, new byte[513])) {
            Files.write(target, invalid);
            assertThrows(Exception.class, () -> storage.read(target, Map.class));
            assertArrayEquals(invalid, Files.readAllBytes(target));
        }
    }

    @Test void exactBoundaryIsAcceptedAndNextByteRequiresCompression() throws Exception {
        Path target = root.resolve("saved.json");
        var value = Map.of("text", "x".repeat(1000));
        long length = json.writeValueAsBytes(value).length;
        new ReviewSnapshotStorage(length, length).write(target, value);
        assertEquals(length, Files.size(target));
        assertEquals('{', Files.readAllBytes(target)[0]);
        new ReviewSnapshotStorage(length - 1, length).write(target, value);
        assertEquals(0x1f, Files.readAllBytes(target)[0] & 0xff);
        assertEquals(value, new ReviewSnapshotStorage(length - 1, length).read(target, Map.class));
    }
}
