package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardModels.Fact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Synthetic TempDir only. No database, source files, network, or production configuration. */
class DerivedDayCacheTest {
    @TempDir Path temporary;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate DAY = LocalDate.of(2026, 10, 8);
    private static final String ACCOUNT = "synthetic-account";

    private Path root() { return temporary.resolve("cache"); }
    private DerivedDayCache cache() { return new DerivedDayCache(root()); }
    private static DerivedDayCache.Key key() { return key(ACCOUNT, 2); }
    private static DerivedDayCache.Key key(String account, int rows) {
        return new DerivedDayCache.Key(account, DAY, 1, "a".repeat(64), "MANUAL_HISTORY", rows);
    }
    private static List<Fact> facts() {
        return List.of(new Fact(DAY, "synthetic-1", "合成\"素材\n名称", DAY.minusDays(2),
                        new BigDecimal("100.00"), new BigDecimal("1E+3"), new BigDecimal("0.000"), null,
                        new BigDecimal("0.123456789012345678901234567890123456789")),
                new Fact(DAY, "-2", null, null, null, BigDecimal.ZERO, null, new BigDecimal("-0.25"), null));
    }
    private Path onlyCacheFile() throws IOException {
        try (var files = Files.walk(root())) {
            return files.filter(path -> path.getFileName().toString().endsWith(".cache")).findFirst().orElseThrow();
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void rewritePayload(Path file, Consumer<ObjectNode> change) throws Exception {
        byte[] original = Files.readAllBytes(file);
        ObjectNode payload = (ObjectNode) JSON.readTree(original, 65, original.length - 65);
        change.accept(payload);
        byte[] updated = JSON.writeValueAsBytes(payload);
        Files.write(file, (sha(updated) + "\n" + new String(updated, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
    }

    @Test void missesAreSideEffectFreeAndConstructorDoesNotTouchDisk() throws IOException {
        DerivedDayCache cache = cache();
        assertThat(Files.exists(root())).isFalse();
        assertThat(cache.load(key())).isEmpty();
        assertThat(cache.isAccountBlocked(ACCOUNT)).isFalse();
        cache.unblockAccount(ACCOUNT);
        assertThat(Files.exists(root())).isFalse();
        Path absentParent = temporary.resolve("absent").resolve("cache");
        DerivedDayCache delayed = new DerivedDayCache(absentParent);
        assertThat(delayed.load(key())).isEmpty();
        assertThatThrownBy(() -> delayed.save(key(), facts())).isInstanceOf(DerivedDayCache.CacheException.class)
                .hasMessage("CACHE_PATH_UNSAFE");
        assertThat(Files.exists(absentParent.getParent())).isFalse();
    }

    @Test void roundTripsAllFieldsAndBigDecimalScaleWithoutChangingNullZeroOrOrder() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        List<Fact> result = cache.load(key()).orElseThrow();
        assertThat(result).isEqualTo(facts());
        assertThat(result.getFirst().spend().scale()).isEqualTo(2);
        assertThat(result.getFirst().gmv().scale()).isEqualTo(-3);
        assertThat(result.getFirst().impressions().scale()).isEqualTo(3);
        assertThat(result.getFirst().clicks()).isNull();
        assertThat(result.get(1).gmv()).isEqualTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> result.add(facts().getFirst())).isInstanceOf(UnsupportedOperationException.class);
        Path file = onlyCacheFile();
        assertThat(file.getParent().getFileName().toString()).matches("[a-f0-9]{64}");
        assertThat(file.getFileName().toString()).matches("[a-f0-9]{64}\\.cache");
        assertThat(root().relativize(file).toString()).doesNotContain(ACCOUNT, "2026", "synthetic-1");
        byte[] bytes = Files.readAllBytes(file);
        assertThat(new String(bytes, 0, 64, StandardCharsets.US_ASCII))
                .isEqualTo(sha(java.util.Arrays.copyOfRange(bytes, 65, bytes.length)));
        assertThat(new DerivedDayCache(root()).load(key()).orElseThrow()).isEqualTo(facts());
    }

    @Test void emptyVerifiedDayRemainsEmptyNotMissing() {
        DerivedDayCache cache = cache(); cache.save(key(ACCOUNT, 0), List.of());
        assertThat(cache.load(key(ACCOUNT, 0))).hasValue(List.of());
    }

    @Test void everyBindingFieldChangesTheCacheIdentity() {
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        List<DerivedDayCache.Key> others = List.of(
                key("synthetic-other", 2), new DerivedDayCache.Key(ACCOUNT, DAY.plusDays(1), 1, "a".repeat(64), "MANUAL_HISTORY", 2),
                new DerivedDayCache.Key(ACCOUNT, DAY, 2, "a".repeat(64), "MANUAL_HISTORY", 2),
                new DerivedDayCache.Key(ACCOUNT, DAY, 1, "b".repeat(64), "MANUAL_HISTORY", 2),
                new DerivedDayCache.Key(ACCOUNT, DAY, 1, "a".repeat(64), "MCP_AUTO", 2), key(ACCOUNT, 1));
        for (var other : others) assertThat(cache.load(other)).isEmpty();
        assertThat(cache.load(key())).hasValue(facts());
    }

    @Test void corruptedOrTruncatedFileMissesAndCanBeRebuiltAtomically() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        byte[] bytes = Files.readAllBytes(file); bytes[bytes.length - 2] ^= 1; Files.write(file, bytes);
        assertThat(cache.load(key())).isEmpty();
        cache.save(key(), facts()); assertThat(cache.load(key())).hasValue(facts());
        Files.writeString(file, "truncated"); assertThat(cache.load(key())).isEmpty();
        cache.save(key(), facts()); assertThat(cache.load(key())).hasValue(facts());
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList()).containsExactly(file.getFileName().toString());
        }
    }

    @Test void payloadRulesIdentityTypesCountsDatesDuplicatesAndExtraFieldsAreCheckedEvenWithValidChecksum() throws Exception {
        List<Consumer<ObjectNode>> invalidations = List.of(
                node -> node.put("ruleVersion", "retired-rule"),
                node -> ((ObjectNode) node.get("key")).put("accountId", "other-synthetic"),
                node -> ((ObjectNode) node.get("key")).put("versionId", "1"),
                node -> ((ObjectNode) node.get("key")).put("versionId", 1.0),
                node -> ((ArrayNode) node.get("facts")).remove(0),
                node -> ((ObjectNode) node.get("facts").get(0)).put("date", DAY.plusDays(1).toString()),
                node -> ((ObjectNode) node.get("facts").get(1)).put("materialId", "synthetic-1"),
                node -> ((ObjectNode) node.get("facts").get(0)).put("spend", 100),
                node -> ((ObjectNode) node.get("facts").get(0)).put("spend", "NaN"),
                node -> ((ObjectNode) node.get("facts").get(0)).put("createdDate", "2026-10-08T12:00:00"),
                node -> ((ObjectNode) node.get("facts").get(0)).remove("clicks"),
                node -> node.put("unexpected", "rejected"));
        DerivedDayCache cache = cache();
        for (Consumer<ObjectNode> invalidation : invalidations) {
            cache.save(key(), facts()); rewritePayload(onlyCacheFile(), invalidation);
            assertThat(cache.load(key())).isEmpty();
        }
    }

    @Test void duplicatedJsonPropertiesAndTrailingTokensAreCacheMisses() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        byte[] original = Files.readAllBytes(file);
        String payload = new String(original, 65, original.length - 65, StandardCharsets.UTF_8);
        for (String malformed : List.of(payload.replace("{\"ruleVersion\":", "{\"ruleVersion\":\"x\",\"ruleVersion\":"), payload + " {}")) {
            Files.writeString(file, sha(malformed.getBytes(StandardCharsets.UTF_8)) + "\n" + malformed);
            assertThat(cache.load(key())).isEmpty();
        }
    }

    @Test void invalidInputDoesNotReplaceAnExistingGoodProjectionOrCreateParents() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        byte[] previous = Files.readAllBytes(file);
        assertThatThrownBy(() -> cache.save(key(), List.of(facts().getFirst(), facts().getFirst())))
                .hasMessage("CACHE_FACTS_INVALID");
        assertThatThrownBy(() -> cache.save(key(), List.of())).hasMessage("CACHE_FACTS_INVALID");
        assertThatThrownBy(() -> cache.save(key(null, 2), facts())).hasMessage("CACHE_KEY_INVALID");
        assertThat(Files.readAllBytes(file)).isEqualTo(previous);
        assertThat(cache.load(key())).hasValue(facts());
    }

    @Test void oversizedFilesAreMissesWithoutAllocatingThePayload() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.position(DerivedDayCache.MAX_FILE_BYTES); channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        assertThat(cache.load(key())).isEmpty();
        cache.save(key(), facts()); assertThat(cache.load(key())).hasValue(facts());
    }

    @Test void streamedSaveEnforcesSizeBoundAndRemovesOnlyItsOwnTemporaryFile() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        Path original = onlyCacheFile(); byte[] before = Files.readAllBytes(original);
        Path sibling = original.getParent().resolve("unrelated-evidence.keep"); Files.writeString(sibling, "SYNTHETIC");
        List<Fact> large = new ArrayList<>(); String name = "S".repeat(8192);
        for (int i = 0; i < 4200; i++) large.add(new Fact(DAY, "synthetic-" + i, name, null, null, null, null, null, null));
        assertThatThrownBy(() -> cache.save(key(ACCOUNT, large.size()), large)).hasMessage("CACHE_SIZE_LIMIT");
        assertThat(Files.readAllBytes(original)).isEqualTo(before);
        assertThat(Files.readString(sibling)).isEqualTo("SYNTHETIC");
        try (var files = Files.list(original.getParent())) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList()).isEmpty();
        }
    }

    @Test void privatePermissionsAreUsedWhenPosixIsSupported() throws Exception {
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        assertThat(Files.getPosixFilePermissions(root())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file.getParent())).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test void permissiveExistingRootIsRejectedWithoutChmodEvenBeforeAnAccountDirectoryExists() throws Exception {
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
        Files.createDirectory(root());
        var permissive = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(root(), permissive);
        DerivedDayCache cache = cache(); // permissions must not break construction/startup
        assertThatThrownBy(() -> cache.load(key())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.isAccountBlocked(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.save(key(), facts())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.blockAccount(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.unblockAccount(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThat(Files.getPosixFilePermissions(root())).isEqualTo(permissive);
        try (var files = Files.list(root())) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void permissiveAccountProjectionAndBlockPermissionsAreRejectedWithoutRepairingThem() throws Exception {
        assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        Path file = onlyCacheFile(), account = file.getParent();
        var directoryMode = PosixFilePermissions.fromString("rwx------");
        var fileMode = PosixFilePermissions.fromString("rw-------");
        var sharedFileMode = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(account, PosixFilePermissions.fromString("rwxr-x---"));
        assertThatThrownBy(() -> cache.load(key())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.save(key(), facts())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        Files.setPosixFilePermissions(account, directoryMode);
        Files.setPosixFilePermissions(file, sharedFileMode);
        assertThatThrownBy(() -> cache.load(key())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.save(key(), facts())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThat(Files.getPosixFilePermissions(file)).isEqualTo(sharedFileMode);
        Files.setPosixFilePermissions(file, fileMode);
        cache.blockAccount(ACCOUNT);
        Path marker = account.resolve(".account-blocked");
        Files.setPosixFilePermissions(marker, sharedFileMode);
        assertThatThrownBy(() -> cache.load(key())).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.isAccountBlocked(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.blockAccount(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThatThrownBy(() -> cache.unblockAccount(ACCOUNT)).hasMessage("CACHE_PERMISSIONS_UNSAFE");
        assertThat(Files.getPosixFilePermissions(marker)).isEqualTo(sharedFileMode);
        Files.setPosixFilePermissions(marker, fileMode);
        cache.unblockAccount(ACCOUNT);
        assertThat(cache.load(key())).hasValue(facts());
    }

    @Test void nonDirectoryRootsAndNonRegularCacheFilesAreRejected() throws Exception {
        Files.writeString(root(), "SYNTHETIC");
        assertThatThrownBy(() -> cache().load(key())).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache().save(key(), facts())).hasMessage("CACHE_PATH_UNSAFE");
        Files.delete(root()); cache().save(key(), facts()); Path file = onlyCacheFile();
        Files.delete(file); Files.createDirectory(file);
        assertThatThrownBy(() -> cache().load(key())).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache().save(key(), facts())).hasMessage("CACHE_PATH_UNSAFE");
    }

    private static void symlinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException unavailable) {
            assumeTrue(false, "Symlinks unavailable on this test filesystem");
        }
    }

    @Test void rootAncestorAndLeafSymlinksAreRejectedWithoutTouchingTheirTargets() throws Exception {
        Path outside = temporary.resolve("outside"); Files.createDirectory(outside);
        symlinkOrSkip(root(), outside);
        assertThatThrownBy(() -> cache().load(key())).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache().save(key(), facts())).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> new DerivedDayCache(root().resolve("child")).save(key(), facts())).hasMessage("CACHE_PATH_UNSAFE");
        Files.delete(root()); cache().save(key(), facts()); Path file = onlyCacheFile();
        Path outsideFile = outside.resolve("must-stay.txt"); Files.writeString(outsideFile, "DO_NOT_TOUCH");
        Files.delete(file); symlinkOrSkip(file, outsideFile);
        assertThatThrownBy(() -> cache().load(key())).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache().save(key(), facts())).hasMessage("CACHE_PATH_UNSAFE");
        assertThat(Files.readString(outsideFile)).isEqualTo("DO_NOT_TOUCH");
    }

    @Test void durableAccountBlockSurvivesRestartAndWritesUntilExplicitSuccessfulRepairUnblocks() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        cache.save(key("synthetic-other", 2), facts());
        Path oldFile = onlyCacheFile(); byte[] before = Files.readAllBytes(oldFile);
        cache.blockAccount(ACCOUNT);
        DerivedDayCache restarted = new DerivedDayCache(root());
        assertThat(restarted.isAccountBlocked(ACCOUNT)).isTrue();
        assertThat(restarted.load(key())).isEmpty();
        assertThat(restarted.load(key("synthetic-other", 2))).hasValue(facts());
        restarted.save(key(), facts());
        assertThat(restarted.isAccountBlocked(ACCOUNT)).isTrue();
        assertThat(restarted.load(key())).isEmpty();
        restarted.unblockAccount(ACCOUNT);
        assertThat(new DerivedDayCache(root()).isAccountBlocked(ACCOUNT)).isFalse();
        assertThat(restarted.load(key())).hasValue(facts());
        assertThat(Files.readAllBytes(oldFile)).isEqualTo(before);
    }

    @Test void evenDamagedBlockMarkersRemainBlockedAndUnblockDoesNotRemoveProjections() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts()); Path file = onlyCacheFile();
        cache.blockAccount(ACCOUNT);
        Path marker = file.getParent().resolve(".account-blocked"); Files.writeString(marker, "");
        assertThat(cache.isAccountBlocked(ACCOUNT)).isTrue();
        cache.unblockAccount(ACCOUNT);
        assertThat(Files.isRegularFile(file)).isTrue();
        assertThat(cache.load(key())).hasValue(facts());
    }

    @Test void nonRegularBlockMarkerCannotBeFollowedReplacedOrDeleted() throws Exception {
        DerivedDayCache cache = cache(); cache.save(key(), facts());
        Path marker = onlyCacheFile().getParent().resolve(".account-blocked"); Files.createDirectory(marker);
        assertThatThrownBy(() -> cache.isAccountBlocked(ACCOUNT)).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache.blockAccount(ACCOUNT)).hasMessage("CACHE_PATH_UNSAFE");
        assertThatThrownBy(() -> cache.unblockAccount(ACCOUNT)).hasMessage("CACHE_PATH_UNSAFE");
        assertThat(Files.isDirectory(marker)).isTrue();
    }
}
