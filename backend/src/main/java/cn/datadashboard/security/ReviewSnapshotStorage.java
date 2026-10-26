package cn.datadashboard.security;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** One atomic private snapshot: legacy JSON for small results, GZIP for large ones.
 * The historical .json pathname is retained. Readers detect the GZIP magic.
 * Byte limits cover both on-disk and decoded bytes; no material rows are omitted.
 */
final class ReviewSnapshotStorage {
    static final long STORED_LIMIT = 16L * 1024 * 1024;
    static final long DECODED_LIMIT = 64L * 1024 * 1024;
    private final long storedLimit;
    private final long decodedLimit;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE).build();

    ReviewSnapshotStorage() { this(STORED_LIMIT, DECODED_LIMIT); }

    ReviewSnapshotStorage(long storedLimit, long decodedLimit) {
        if (storedLimit < 1 || decodedLimit < storedLimit) throw new IllegalArgumentException();
        this.storedLimit = storedLimit;
        this.decodedLimit = decodedLimit;
    }

    static final class SizeLimitExceeded extends IOException {
        SizeLimitExceeded() { super("REVIEW_SNAPSHOT_TOO_LARGE"); }
    }

    static boolean isSizeLimit(Throwable failure) {
        var seen = new HashSet<Throwable>();
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause())
            if (cause instanceof SizeLimitExceeded) return true;
        return false;
    }

    <T> T read(Path file, Class<T> type) throws IOException {
        try (InputStream decoded = open(file)) {
            T value = json.readValue(decoded, type);
            // Complete the stream, including the GZIP trailer/CRC and byte limits.
            decoded.transferTo(OutputStream.nullOutputStream());
            return value;
        }
    }

    void write(Path target, Object envelope) throws IOException {
        PrivateUserFiles.directory(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("USER_STATE_UNSAFE");
        Path pending = Files.createTempFile(target.getParent(), ".review-", ".tmp");
        try {
            if (Files.getFileStore(pending).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(pending, PosixFilePermissions.fromString("rw-------"));
            Payload expected;
            try {
                expected = writePayload(pending, envelope, false);
            } catch (Exception failure) {
                if (!isSizeLimit(failure)) throw failure;
                // The incomplete temporary JSON is truncated, not the successful target.
                expected = writePayload(pending, envelope, true);
            }
            verifyPayload(pending, expected);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.isSymbolicLink(target)) throw new IOException("USER_STATE_UNSAFE");
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private record Payload(long bytes, byte[] hash) { }

    private Payload writePayload(Path file, Object envelope, boolean compressed) throws IOException {
        MessageDigest digest = digest();
        try (OutputStream raw = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             OutputStream disk = new LimitedOutput(raw, storedLimit);
             OutputStream encoded = compressed ? new GZIPOutputStream(disk, 64 * 1024) : disk;
             LimitedOutput decoded = new LimitedOutput(encoded, decodedLimit);
             DigestOutputStream output = new DigestOutputStream(decoded, digest)) {
            json.writeValue(output, envelope);
            return new Payload(decoded.count, digest.digest());
        }
    }

    private void verifyPayload(Path file, Payload expected) throws IOException {
        MessageDigest digest = digest();
        try (InputStream decoded = open(file); DigestInputStream input = new DigestInputStream(decoded, digest)) {
            long bytes = input.transferTo(OutputStream.nullOutputStream());
            if (bytes != expected.bytes() || !Arrays.equals(digest.digest(), expected.hash()))
                throw new IOException("REVIEW_SNAPSHOT_READBACK_FAILED");
        }
    }

    private InputStream open(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > storedLimit)
            throw new IOException("REVIEW_SNAPSHOT_UNAVAILABLE");
        InputStream raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        try {
            BufferedInputStream disk = new BufferedInputStream(new LimitedInput(raw, storedLimit), 64 * 1024);
            disk.mark(2);
            int first = disk.read(), second = disk.read();
            disk.reset();
            InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(disk, 64 * 1024) : disk;
            return new LimitedInput(decoded, decodedLimit);
        } catch (Throwable failure) {
            raw.close();
            throw failure;
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static final class LimitedOutput extends FilterOutputStream {
        private final long limit;
        private long count;
        LimitedOutput(OutputStream out, long limit) { super(out); this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (count == limit) throw new SizeLimitExceeded();
            out.write(value); count++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - count) throw new SizeLimitExceeded();
            out.write(bytes, offset, length); count += length;
        }
    }

    private static final class LimitedInput extends FilterInputStream {
        private final long limit;
        private long count;
        LimitedInput(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0 && ++count > limit) throw new SizeLimitExceeded();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int read = in.read(bytes, offset, (int) Math.min(length, limit - count + 1));
            if (read > 0 && (count += read) > limit) throw new SizeLimitExceeded();
            return read;
        }
        @Override public long skip(long amount) throws IOException {
            long skipped = 0;
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(0, amount))];
            while (skipped < amount) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, amount - skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
        @Override public boolean markSupported() { return false; }
        @Override public void mark(int limit) { }
        @Override public void reset() throws IOException { throw new IOException("MARK_NOT_SUPPORTED"); }
    }
}
