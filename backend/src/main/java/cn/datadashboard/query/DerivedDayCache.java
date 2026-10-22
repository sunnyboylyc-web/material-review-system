package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardModels.Fact;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Rebuildable, private, verified-day calculation projections. Never an original-fact store.
 * A cache hit binds a caller-verified database version to the projection, but is not a fresh
 * audit of database row bytes. Only call save after the source day's full verification.
 * The SHA header detects accidental corruption, not hostile modification by the service owner.
 * POSIX filesystems also synchronize directory entries after publication/removal. On platforms
 * without POSIX directory synchronization (including Windows), atomic replacement and file force
 * support ordinary restart recovery; sudden-power-loss directory durability is not asserted.
 */
public final class DerivedDayCache {
    public static final String RULE_VERSION = "dashboard-derived-day-v1";
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int HEADER_BYTES = 65;
    private static final int MAX_ROWS = 100_000;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> KEY_FIELDS = Set.of("accountId", "date", "versionId", "contentHash", "sourceType", "rowCount");
    private static final Set<String> FACT_FIELDS = Set.of("date", "materialId", "materialName", "createdDate",
            "spend", "gmv", "impressions", "clicks", "orders");
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final String BLOCK_FILE = ".account-blocked";
    private static final byte[] BLOCK_CONTENT = (RULE_VERSION + "\nSOURCE_REVALIDATION_REQUIRED\n").getBytes(StandardCharsets.US_ASCII);
    private final Path root;

    public record Key(String accountId, LocalDate date, long versionId, String contentHash,
                      String sourceType, int rowCount) { }

    /** Fixed codes only: no file paths, business values, credentials, or nested IO messages. */
    public static final class CacheException extends RuntimeException {
        private final String code;
        public CacheException(String code) { super(code, null, false, false); this.code = code; }
        public String code() { return code; }
    }

    public DerivedDayCache(Path root) {
        if (root == null) throw fail("CACHE_PATH_UNSAFE");
        this.root = root.toAbsolutePath().normalize();
        if (this.root.getParent() == null) throw fail("CACHE_PATH_UNSAFE");
    }

    public Optional<List<Fact>> load(Key key) {
        validateKey(key);
        if (isAccountBlocked(key.accountId())) return Optional.empty();
        return readProjection(key);
    }

    private Optional<List<Fact>> readProjection(Key key) {
        try {
            Path directory = accountDirectory(key);
            if (!privateDirectories(directory)) return Optional.empty();
            Path file = file(key);
            BasicFileAttributes attributes = attributes(file);
            if (attributes == null) return Optional.empty();
            regular(attributes);
            privatePermissions(file, false);
            if (attributes.size() < HEADER_BYTES || attributes.size() > MAX_FILE_BYTES) return Optional.empty();
            byte[] bytes = boundedRead(file);
            if (bytes.length < HEADER_BYTES || bytes[64] != '\n') return Optional.empty();
            String expected = new String(bytes, 0, 64, StandardCharsets.US_ASCII);
            if (!expected.matches("[0-9a-f]{64}")) return Optional.empty();
            MessageDigest digest = digest();
            digest.update(bytes, HEADER_BYTES, bytes.length - HEADER_BYTES);
            if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) return Optional.empty();
            try {
                JsonNode payload = JSON.readTree(bytes, HEADER_BYTES, bytes.length - HEADER_BYTES);
                fields(payload, Set.of("ruleVersion", "key", "facts"));
                require(RULE_VERSION.equals(text(payload, "ruleVersion", false)), "CACHE_INVALID");
                JsonNode storedKey = payload.get("key");
                fields(storedKey, KEY_FIELDS);
                require(sameKey(key, storedKey), "CACHE_INVALID");
                JsonNode rows = payload.get("facts");
                require(rows != null && rows.isArray() && rows.size() == key.rowCount(), "CACHE_INVALID");
                List<Fact> facts = new ArrayList<>(key.rowCount());
                Set<String> ids = new HashSet<>();
                for (JsonNode row : rows) {
                    fields(row, FACT_FIELDS);
                    Fact fact = new Fact(date(row, "date", false), text(row, "materialId", false),
                            text(row, "materialName", true), date(row, "createdDate", true),
                            decimal(row, "spend"), decimal(row, "gmv"), decimal(row, "impressions"),
                            decimal(row, "clicks"), decimal(row, "orders"));
                    validateFact(key, fact, ids);
                    facts.add(fact);
                }
                return Optional.of(List.copyOf(facts));
            } catch (RuntimeException invalid) {
                // Corrupt, old-rule, or mismatched projections are a miss: re-verify the source day.
                return Optional.empty();
            }
        } catch (TooLarge invalid) {
            return Optional.empty();
        } catch (CacheException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw fail("CACHE_IO_FAILED");
        }
    }

    public void save(Key key, List<Fact> facts) {
        validateKey(key);
        if (facts == null || facts.size() != key.rowCount()) throw fail("CACHE_FACTS_INVALID");
        // Validate the whole input before creating or replacing any file.
        Set<String> ids = new HashSet<>();
        for (Fact fact : facts) validateFact(key, fact, ids);
        Path temporary = null;
        try {
            ensureRoot();
            Path directory = accountDirectory(key);
            ensureDirectory(directory);
            Path target = file(key);
            BasicFileAttributes old = attributes(target);
            if (old != null) { regular(old); privatePermissions(target, false); }
            temporary = Files.createTempFile(directory, ".day-", ".tmp", privateAttributes(directory, false));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW)) {
                write(channel, ("0".repeat(64) + "\n").getBytes(StandardCharsets.US_ASCII));
                PayloadWriter writer = new PayloadWriter(channel);
                writer.write("{\"ruleVersion\":");
                writer.write(JSON.writeValueAsBytes(RULE_VERSION));
                writer.write(",\"key\":");
                writer.write(JSON.writeValueAsBytes(keyNode(key)));
                writer.write(",\"facts\":[");
                for (int index = 0; index < facts.size(); index++) {
                    if (index > 0) writer.write(",");
                    writer.write(JSON.writeValueAsBytes(factNode(facts.get(index))));
                }
                writer.write("]}");
                channel.position(0);
                write(channel, HexFormat.of().formatHex(writer.digest.digest()).getBytes(StandardCharsets.US_ASCII));
                channel.force(true);
            }
            // Recheck before publication; never follow a symlink or replace a non-regular target.
            require(privateDirectories(directory), "CACHE_PATH_UNSAFE");
            old = attributes(target);
            if (old != null) { regular(old); privatePermissions(target, false); }
            regular(Objects.requireNonNull(attributes(temporary)));
            privatePermissions(temporary, false);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            syncDirectory(directory);
            // During a strict repair the block deliberately remains until all days are rebuilt.
            if (!readProjection(key).filter(facts::equals).isPresent()) throw fail("CACHE_WRITE_VERIFY_FAILED");
        } catch (TooLarge failure) {
            throw fail("CACHE_SIZE_LIMIT");
        } catch (CacheException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw fail("CACHE_IO_FAILED");
        } finally {
            if (temporary != null) {
                // Only our exact, unpublished temp path; never clean other cache entries.
                try {
                    if (directoryChain(temporary.getParent())) Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException ignored) { /* no private details in an exception */ }
            }
        }
    }

    /** A durable fail-closed flag. Saving a verified day never clears an account-wide block. */
    public void blockAccount(String accountId) {
        validateAccount(accountId);
        Path temporary = null;
        try {
            ensureRoot();
            Path directory = accountDirectory(accountId);
            ensureDirectory(directory);
            Path target = directory.resolve(BLOCK_FILE);
            BasicFileAttributes old = attributes(target);
            if (old != null) { regular(old); privatePermissions(target, false); }
            temporary = Files.createTempFile(directory, ".block-", ".tmp", privateAttributes(directory, false));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW)) {
                write(channel, BLOCK_CONTENT); channel.force(true);
            }
            require(privateDirectories(directory), "CACHE_PATH_UNSAFE");
            old = attributes(target);
            if (old != null) { regular(old); privatePermissions(target, false); }
            regular(Objects.requireNonNull(attributes(temporary)));
            privatePermissions(temporary, false);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            syncDirectory(directory);
            require(isAccountBlocked(accountId), "CACHE_WRITE_VERIFY_FAILED");
        } catch (CacheException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw fail("CACHE_IO_FAILED");
        } finally {
            if (temporary != null) {
                try {
                    if (directoryChain(temporary.getParent())) Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException ignored) { }
            }
        }
    }

    public boolean isAccountBlocked(String accountId) {
        validateAccount(accountId);
        try {
            Path directory = accountDirectory(accountId);
            if (!privateDirectories(directory)) return false;
            Path markerPath = directory.resolve(BLOCK_FILE);
            BasicFileAttributes marker = attributes(markerPath);
            if (marker == null) return false;
            regular(marker);
            privatePermissions(markerPath, false);
            // Even a corrupt or empty marker blocks reuse; do not silently unblock on corruption.
            return true;
        } catch (CacheException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw fail("CACHE_IO_FAILED");
        }
    }

    /** Caller must finish a strict source audit and rebuild every affected day before calling. */
    public void unblockAccount(String accountId) {
        validateAccount(accountId);
        try {
            Path directory = accountDirectory(accountId);
            if (!privateDirectories(directory)) return;
            Path marker = directory.resolve(BLOCK_FILE);
            BasicFileAttributes found = attributes(marker);
            if (found == null) return;
            regular(found);
            privatePermissions(marker, false);
            Files.delete(marker);
            syncDirectory(directory);
            require(!isAccountBlocked(accountId), "CACHE_WRITE_VERIFY_FAILED");
        } catch (CacheException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw fail("CACHE_IO_FAILED");
        }
    }

    private Path accountDirectory(Key key) { return accountDirectory(key.accountId()); }
    private Path accountDirectory(String accountId) { return root.resolve(sha(accountId.getBytes(StandardCharsets.UTF_8))); }
    private Path file(Key key) {
        ObjectNode binding = JSON.createObjectNode().put("ruleVersion", RULE_VERSION);
        binding.set("key", keyNode(key));
        return accountDirectory(key).resolve(sha(JSON.writeValueAsBytes(binding)) + ".cache");
    }

    private void ensureRoot() throws IOException {
        // Do not create parents outside the exact cache root supplied by the caller.
        require(directoryChain(root.getParent()), "CACHE_PATH_UNSAFE");
        ensureDirectory(root);
    }

    private static void ensureDirectory(Path path) throws IOException {
        BasicFileAttributes found = attributes(path);
        boolean created = false;
        if (found == null) {
            try { Files.createDirectory(path, privateAttributes(path.getParent(), true)); created = true; }
            catch (java.nio.file.FileAlreadyExistsException raced) { /* validated below */ }
        }
        require(directoryChain(path), "CACHE_PATH_UNSAFE");
        privatePermissions(path, true);
        if (created) {
            syncDirectory(path);
            // Persist the newly created directory entry, including a newly created cache root.
            syncDirectory(path.getParent());
        }
    }

    private boolean privateDirectories(Path directory) throws IOException {
        if (!directoryChain(root)) return false;
        privatePermissions(root, true);
        if (!directoryChain(directory)) return false;
        privatePermissions(directory, true);
        return true;
    }

    private static void privatePermissions(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            require(Files.getPosixFilePermissions(path, NOFOLLOW).equals(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")), "CACHE_PERMISSIONS_UNSAFE");
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        require(directoryChain(directory), "CACHE_PATH_UNSAFE");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, NOFOLLOW)) {
                channel.force(true);
            }
        }
    }

    /** Check every existing ancestor, including filesystem root; not merely the leaf. */
    private static boolean directoryChain(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) throw fail("CACHE_PATH_UNSAFE");
        BasicFileAttributes first = attributes(current);
        require(first != null && first.isDirectory() && !first.isSymbolicLink(), "CACHE_PATH_UNSAFE");
        for (Path segment : path) {
            current = current.resolve(segment);
            BasicFileAttributes found = attributes(current);
            if (found == null) return false;
            require(found.isDirectory() && !found.isSymbolicLink(), "CACHE_PATH_UNSAFE");
        }
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try { return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW); }
        catch (NoSuchFileException missing) { return null; }
    }
    private static void regular(BasicFileAttributes found) {
        require(found.isRegularFile() && !found.isSymbolicLink(), "CACHE_PATH_UNSAFE");
    }
    private static FileAttribute<?>[] privateAttributes(Path parent, boolean directory) throws IOException {
        return Files.getFileStore(parent).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))}
                : new FileAttribute<?>[0];
    }

    private static byte[] boundedRead(Path file) throws IOException {
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, NOFOLLOW));
             var bytes = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            for (int count; (count = channel.read(buffer)) >= 0;) {
                if (count == 0) continue;
                if (bytes.size() + count > MAX_FILE_BYTES) throw new TooLarge();
                bytes.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return bytes.toByteArray();
        }
    }

    private static ObjectNode keyNode(Key key) {
        return JSON.createObjectNode().put("accountId", key.accountId()).put("date", key.date().toString())
                .put("versionId", key.versionId()).put("contentHash", key.contentHash())
                .put("sourceType", key.sourceType()).put("rowCount", key.rowCount());
    }
    private static boolean sameKey(Key key, JsonNode stored) {
        JsonNode version = stored.get("versionId"), rows = stored.get("rowCount");
        return key.accountId().equals(text(stored, "accountId", false))
                && key.date().toString().equals(text(stored, "date", false))
                && version != null && version.isIntegralNumber() && version.canConvertToLong() && version.longValue() == key.versionId()
                && key.contentHash().equals(text(stored, "contentHash", false))
                && key.sourceType().equals(text(stored, "sourceType", false))
                && rows != null && rows.isIntegralNumber() && rows.canConvertToInt() && rows.intValue() == key.rowCount();
    }
    private static ObjectNode factNode(Fact fact) {
        ObjectNode row = JSON.createObjectNode().put("date", fact.date().toString())
                .put("materialId", fact.materialId()).put("materialName", fact.materialName())
                .put("createdDate", fact.createdDate() == null ? null : fact.createdDate().toString());
        putDecimal(row, "spend", fact.spend()); putDecimal(row, "gmv", fact.gmv());
        putDecimal(row, "impressions", fact.impressions()); putDecimal(row, "clicks", fact.clicks());
        putDecimal(row, "orders", fact.orders());
        return row;
    }
    private static void putDecimal(ObjectNode row, String name, BigDecimal value) {
        row.put(name, value == null ? null : value.toString());
    }
    private static void validateKey(Key key) {
        require(key != null && key.accountId() != null && !key.accountId().isBlank() && key.accountId().length() <= 64
                && key.date() != null && key.versionId() > 0 && key.contentHash() != null
                && key.contentHash().matches("[0-9a-f]{64}") && key.sourceType() != null
                && Set.of("MCP_AUTO", "MANUAL_HISTORY").contains(key.sourceType())
                && key.rowCount() >= 0 && key.rowCount() <= MAX_ROWS, "CACHE_KEY_INVALID");
    }
    private static void validateAccount(String accountId) {
        require(accountId != null && !accountId.isBlank() && accountId.length() <= 64, "CACHE_KEY_INVALID");
    }
    private static void validateFact(Key key, Fact fact, Set<String> ids) {
        require(fact != null && key.date().equals(fact.date()) && fact.materialId() != null
                && !fact.materialId().isBlank() && fact.materialId().length() <= 1024 && ids.add(fact.materialId())
                && (fact.materialName() == null || fact.materialName().length() <= 8192), "CACHE_FACTS_INVALID");
        // Length bounds are cache eligibility only. Rejected projections must fall back to source validation.
        for (BigDecimal value : new BigDecimal[]{fact.spend(), fact.gmv(), fact.impressions(), fact.clicks(), fact.orders()}) {
            require(value == null || value.toString().length() <= 1024, "CACHE_FACTS_INVALID");
        }
    }
    private static void fields(JsonNode node, Set<String> names) {
        require(node != null && node.isObject() && node.size() == names.size()
                && node.properties().stream().allMatch(entry -> names.contains(entry.getKey())), "CACHE_INVALID");
    }
    private static String text(JsonNode node, String name, boolean nullable) {
        JsonNode value = node.get(name);
        require(value != null && (value.isTextual() || nullable && value.isNull()), "CACHE_INVALID");
        return value.isNull() ? null : value.stringValue();
    }
    private static LocalDate date(JsonNode node, String name, boolean nullable) {
        String value = text(node, name, nullable);
        if (value == null) return null;
        LocalDate parsed = LocalDate.parse(value);
        require(parsed.toString().equals(value), "CACHE_INVALID");
        return parsed;
    }
    private static BigDecimal decimal(JsonNode node, String name) {
        String value = text(node, name, true);
        if (value == null) return null;
        require(value.length() <= 1024, "CACHE_INVALID");
        BigDecimal parsed = new BigDecimal(value);
        require(parsed.toString().equals(value), "CACHE_INVALID");
        return parsed;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw fail("CACHE_DIGEST_UNAVAILABLE"); }
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static CacheException fail(String code) { return new CacheException(code); }
    private static void require(boolean valid, String code) { if (!valid) throw fail(code); }
    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    private static final class TooLarge extends IOException { }
    private static final class PayloadWriter {
        private final FileChannel channel;
        private final MessageDigest digest = digest();
        private int size = HEADER_BYTES;
        private PayloadWriter(FileChannel channel) { this.channel = channel; }
        private void write(String value) throws IOException { write(value.getBytes(StandardCharsets.UTF_8)); }
        private void write(byte[] bytes) throws IOException {
            if ((long) size + bytes.length > MAX_FILE_BYTES) throw new TooLarge();
            DerivedDayCache.write(channel, bytes); digest.update(bytes); size += bytes.length;
        }
    }
}
