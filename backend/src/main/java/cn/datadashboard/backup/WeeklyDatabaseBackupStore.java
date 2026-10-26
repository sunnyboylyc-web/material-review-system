package cn.datadashboard.backup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Read-only view of the backup worker's published generation. Never starts or restores a backup. */
public final class WeeklyDatabaseBackupStore {
    public static final String SCHEDULE = "每周日23:00（上海时间）";
    private static final String VALIDATION = "DUMP_EXIT_AND_HASH_READBACK";
    private static final int METADATA_LIMIT = 8192;
    private final Path root;
    private final JsonMapper json = JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Semaphore downloadSlot = new Semaphore(1);

    public record Latest(String generation, String createdAt, long bytes, String sha256,
                         String validation, boolean restoreTested, String compression, long sqlBytes, String sqlSha256,
                         String scope, String startDate, String endDate) { }
    public record LastAttempt(String status, String startedAt, String completedAt, String errorCode) { }
    public record Status(boolean available, Latest latest, LastAttempt lastAttempt, String schedule, int retention) { }

    public static final class Conflict extends RuntimeException {
        public Conflict(String code) { super(code); }
    }

    public WeeklyDatabaseBackupStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Status status() throws IOException {
        // A concurrent publication may unlink the old generation between the two small reads.
        for (int attempt = 0; attempt < 2; attempt++) {
            Latest latest = current();
            try {
                if (latest != null) {
                    try (var file = openRegular(sql(latest), latest.bytes())) { /* metadata only */ }
                }
                return new Status(latest != null, latest, lastAttempt(), SCHEDULE, 1);
            } catch (NoSuchFileException changed) {
                if (attempt != 0) throw unavailable();
            }
        }
        throw unavailable();
    }

    public Download openDownload(String generation) throws IOException {
        if (generation == null || !generation.matches("[a-f0-9]{32}")) throw new IllegalArgumentException();
        if (!downloadSlot.tryAcquire()) throw new Conflict("BACKUP_DOWNLOAD_BUSY");
        SeekableByteChannel file = null;
        try {
            Latest latest = current();
            if (latest == null) throw new Conflict("BACKUP_NOT_AVAILABLE");
            if (!latest.generation().equals(generation)) throw new Conflict("BACKUP_GENERATION_CHANGED");
            try { file = openRegular(sql(latest), latest.bytes()); }
            catch (NoSuchFileException missing) {
                Latest now = current();
                if (now != null && !now.generation().equals(generation)) throw new Conflict("BACKUP_GENERATION_CHANGED");
                throw unavailable();
            }
            // Verify and rewind this same opened handle, so later deletion/publication cannot switch the bytes.
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = ByteBuffer.allocate(64 * 1024);
            long read = 0;
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
            for (int count; (count = file.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw unavailable();
                read = Math.addExact(read, count);
                if (read > latest.bytes()) throw unavailable();
                buffer.flip(); digest.update(buffer); buffer.clear();
            }
            if (read != latest.bytes() || file.size() != latest.bytes()
                    || !HexFormat.of().formatHex(digest.digest()).equals(latest.sha256())) throw unavailable();
            file.position(0);
            return new Download(latest, file, downloadSlot);
        } catch (IOException | RuntimeException failure) {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            downloadSlot.release(); throw failure;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            if (file != null) try { file.close(); } catch (IOException ignored) { }
            downloadSlot.release(); throw unavailable();
        }
    }

    public static final class Download implements AutoCloseable {
        private final Latest latest;
        private final SeekableByteChannel file;
        private final Semaphore slot;
        private boolean closed;
        private Download(Latest latest, SeekableByteChannel file, Semaphore slot) {
            this.latest = latest; this.file = file; this.slot = slot;
        }
        public Latest latest() { return latest; }
        public String filename() {
            return "scsj-database-" + (latest.startDate() == null ? "" : latest.startDate() + "_" + latest.endDate() + "-") + DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.parse(latest.createdAt())) + (latest.compression().equals("gzip") ? ".sql.gz" : ".sql");
        }
        public void writeTo(OutputStream output) throws IOException {
            var buffer = ByteBuffer.allocate(64 * 1024);
            long total = 0;
            for (int count; (count = file.read(buffer)) != -1;) {
                total += count;
                if (Thread.currentThread().isInterrupted() || total > latest.bytes()) throw unavailable();
                output.write(buffer.array(), 0, count); buffer.clear();
            }
            if (total != latest.bytes()) throw unavailable();
        }
        @Override public void close() throws IOException {
            if (!closed) { closed = true; try { file.close(); } finally { slot.release(); } }
        }
    }

    private Path sql(Latest latest) { return root.resolve("generations").resolve(latest.generation()).resolve(latest.compression().equals("gzip") ? "business.sql.gz" : "business.sql"); }

    private Latest current() throws IOException {
        JsonNode node = metadata(root.resolve("current.json"));
        if (node == null) return null;
        long schema = integer(node, "schemaVersion");
        if ((schema != 1 && schema != 2) || !Set.of("SCSJ_LOCAL_CLOUD", "material_dashboard").contains(text(node, "database"))
                || !VALIDATION.equals(text(node, "validation")) || !node.path("restoreTested").isBoolean()
                || node.path("restoreTested").booleanValue()) throw unavailable();
        String generation = text(node, "generation"), hash = text(node, "sha256"), created = utc(node, "createdAt");
        long bytes = integer(node, "bytes");
        if (!generation.matches("[a-f0-9]{32}") || !hash.matches("[a-f0-9]{64}") || bytes <= 0) throw unavailable();
        String compression = schema == 2 ? text(node, "compression") : "none";
        long sqlBytes = schema == 2 ? integer(node, "sqlBytes") : bytes;
        String sqlHash = schema == 2 ? text(node, "sqlSha256") : hash;
        if ((schema == 2 && !compression.equals("gzip")) || sqlBytes <= 0 || !sqlHash.matches("[a-f0-9]{64}")) throw unavailable();
        String scope = node.has("scope") ? text(node, "scope") : "FULL_DATABASE";
        String start = null, end = null;
        if (!scope.equals("FULL_DATABASE")) {
            if (!scope.equals("STAT_DATE_RANGE")) throw unavailable();
            start = text(node, "startDate"); end = text(node, "endDate");
            try {
                if (!start.matches("\\d{4}-\\d{2}-\\d{2}") || !end.matches("\\d{4}-\\d{2}-\\d{2}")
                        || java.time.LocalDate.parse(start).isAfter(java.time.LocalDate.parse(end))) throw unavailable();
            } catch (java.time.DateTimeException invalid) { throw unavailable(); }
        }
        return new Latest(generation, created, bytes, hash, VALIDATION, false, compression, sqlBytes, sqlHash, scope, start, end);
    }

    private LastAttempt lastAttempt() throws IOException {
        JsonNode node = metadata(root.resolve("last-attempt.json"));
        if (node == null) return null;
        String status = text(node, "status"), started = utc(node, "startedAt");
        if (integer(node, "schemaVersion") != 1 || !Set.of("RUNNING", "SUCCESS", "FAILED").contains(status)) throw unavailable();
        String completed = node.path("completedAt").isNull() ? null : utc(node, "completedAt");
        String error = node.path("errorCode").isNull() ? null : text(node, "errorCode");
        if ((error != null && !error.matches("[A-Z0-9_]{1,80}"))
                || (status.equals("RUNNING") && (completed != null || error != null))
                || (!status.equals("RUNNING") && completed == null)
                || (status.equals("SUCCESS") && error != null)) throw unavailable();
        return new LastAttempt(status, started, completed, error);
    }

    private JsonNode metadata(Path path) throws IOException {
        try (var channel = openRegular(path, -1)) {
            if (channel.size() > METADATA_LIMIT) throw unavailable();
            var bytes = ByteBuffer.allocate(METADATA_LIMIT + 1);
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
            if (bytes.position() > METADATA_LIMIT) throw unavailable();
            try {
                var node = json.readTree(Arrays.copyOf(bytes.array(), bytes.position()));
                if (node == null || !node.isObject()) throw unavailable();
                return node;
            } catch (RuntimeException invalid) { throw unavailable(); }
        } catch (NoSuchFileException absent) { return null; }
    }

    private static String text(JsonNode node, String key) throws IOException {
        if (!node.path(key).isString()) throw unavailable();
        return node.path(key).stringValue();
    }
    private static long integer(JsonNode node, String key) throws IOException {
        var value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw unavailable();
        return value.longValue();
    }
    private static String utc(JsonNode node, String key) throws IOException {
        String value = text(node, key);
        if (value.length() > 40 || !value.endsWith("Z")) throw unavailable();
        try { Instant.parse(value); return value; } catch (RuntimeException invalid) { throw unavailable(); }
    }
    private static IOException unavailable() { return new IOException("DATABASE_BACKUP_UNAVAILABLE"); }

    /** Secure directory-relative opening on Linux prevents parent-symlink swaps as well as final links. */
    private static SeekableByteChannel openRegular(Path path, long expectedBytes) throws IOException {
        var handles = new ArrayList<DirectoryStream<Path>>();
        SeekableByteChannel opened = null;
        try {
            Path current = path.getRoot();
            DirectoryStream<Path> directory = Files.newDirectoryStream(current);
            handles.add(directory);
            for (int i = 0; i < path.getNameCount() - 1; i++) {
                Path name = path.getName(i); current = current.resolve(name);
                if (directory instanceof SecureDirectoryStream<Path> secure) {
                    var attrs = secure.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                    if (!attrs.isDirectory() || attrs.isSymbolicLink()) throw unavailable();
                    directory = secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                } else {
                    // Windows' provider has no SecureDirectoryStream. All ancestors are still checked;
                    // production Linux must provide directory-relative no-follow semantics.
                    if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable();
                    var attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) throw unavailable();
                    directory = Files.newDirectoryStream(current);
                }
                handles.add(directory);
            }
            Path filename = path.getFileName();
            BasicFileAttributes attrs;
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            if (directory instanceof SecureDirectoryStream<Path> secure) {
                attrs = secure.getFileAttributeView(filename, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw unavailable();
                opened = secure.newByteChannel(filename, options);
            } else {
                attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) throw unavailable();
                opened = Files.newByteChannel(path, options);
            }
            if ((expectedBytes >= 0 && attrs.size() != expectedBytes) || opened.size() != attrs.size()) throw unavailable();
            return opened;
        } catch (IOException | RuntimeException failure) {
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            throw failure;
        } finally {
            for (int i = handles.size() - 1; i >= 0; i--) try { handles.get(i).close(); } catch (IOException ignored) { }
        }
    }
}
