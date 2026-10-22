package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LayeredDashboardReaderTest {
    private static final String ACCOUNT = "synthetic-d180";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    @TempDir Path directory;

    @Test void coldWarmAndRestartPreserveStrictSnapshotWithoutWarmRawReads() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1.00000000000000000001"); db.put(DAY.plusDays(1), 2, null);
        var layered = db.layered(directory);
        var cold = layered.readPrepared(ACCOUNT);
        assertThat(cold.snapshot()).isEqualTo(db.strict.read(ACCOUNT));
        int calls = db.rawQueries.get();
        assertThat(layered.readPrepared(ACCOUNT)).isSameAs(cold);
        assertThat(db.rawQueries.get()).isEqualTo(calls);
        assertThat(layered.counters().memoryDaysReused()).isEqualTo(2);
        var restarted = db.layered(directory);
        assertThat(restarted.read(ACCOUNT)).isEqualTo(cold.snapshot());
        assertThat(db.rawQueries.get()).isEqualTo(calls);
        assertThat(restarted.counters().diskDaysLoaded()).isEqualTo(2);
        assertThat(db.manifestQueries.get()).isEqualTo(3);
    }

    @Test void newReplacementWithdrawalAndEarlierBackfillUseCurrentManifest() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "2");
        var reader = db.layered(directory); reader.read(ACCOUNT);
        db.put(DAY.plusDays(1), 2, "3"); reader.read(ACCOUNT);
        assertThat(reader.counters()).isEqualTo(new LayeredDashboardReader.Counters(1, 0, 1, false));
        db.put(DAY, 3, "4"); var replaced = reader.read(ACCOUNT);
        assertThat(reader.counters().rawDaysVerified()).isEqualTo(1);
        assertThat(replaced.facts().getFirst().spend()).isEqualByComparingTo("4");
        db.rows.remove(DAY); var withdrawn = reader.read(ACCOUNT);
        assertThat(withdrawn.days()).hasSize(1);
        assertThat(reader.counters().rawDaysVerified()).isZero();
        db.put(DAY.minusYears(2), 4, "5"); var backfill = reader.read(ACCOUNT);
        assertThat(backfill.days().getFirst().date()).isEqualTo(DAY.minusYears(2));
        assertThat(reader.counters().rawDaysVerified()).isEqualTo(1);
        assertThat(backfill).isEqualTo(db.strict.read(ACCOUNT));
    }

    @Test void cacheCorruptionFallsBackToVerifiedDayAndNeverSilentlyChangesResults() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1e-29");
        var snapshot = db.layered(directory).read(ACCOUNT);
        try (var paths = Files.walk(directory)) {
            Path cached = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.writeString(cached, "truncated synthetic cache");
        }
        var restarted = db.layered(directory);
        assertThat(restarted.read(ACCOUNT)).isEqualTo(snapshot);
        assertThat(restarted.counters().rawDaysVerified()).isEqualTo(1);
    }

    @Test void approvedLayeringDefersUnversionedTamperButStrictAuditQuarantinesAcrossRestart() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1");
        Map<String,Object> original = new HashMap<>(db.rows.get(DAY));
        var reader = db.layered(directory); var before = reader.read(ACCOUNT);
        db.rows.get(DAY).put("raw_fact_json", "tampered-private-value");
        assertThat(reader.read(ACCOUNT)).isEqualTo(before); // Explicit approved detection boundary.
        assertThatThrownBy(() -> reader.inspect(ACCOUNT, DAY, DAY))
                .isInstanceOf(JdbcDashboardSnapshotReader.SnapshotReadException.class)
                .hasMessage("FACT_BYTES_HASH_MISMATCH");
        assertThat(new DerivedDayCache(directory).isAccountBlocked(ACCOUNT)).isTrue();
        assertThatThrownBy(() -> db.layered(directory).read(ACCOUNT))
                .hasMessage("FACT_BYTES_HASH_MISMATCH");
        db.rows.put(DAY, original);
        assertThat(reader.read(ACCOUNT)).isEqualTo(before);
        assertThat(reader.counters().forcedAudit()).isTrue();
        assertThat(new DerivedDayCache(directory).isAccountBlocked(ACCOUNT)).isFalse();
    }

    @Test void differentAccountCannotReuseLatestOrDiskEvenWithSameVersionAndHash() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1");
        var reader = db.layered(directory); reader.read(ACCOUNT);
        assertThatThrownBy(() -> reader.read("another-account"))
                .hasMessage("CURRENT_VERSION_IDENTITY_MISMATCH");
    }

    @Test void concurrentColdQueriesBuildOnceAndReturnImmutablePreparedInput() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1");
        var reader = db.layered(directory);
        try (var pool = Executors.newFixedThreadPool(3)) {
            List<java.util.concurrent.Future<DashboardCalculator.PreparedSnapshot>> jobs = new ArrayList<>();
            for (int i = 0; i < 9; i++) jobs.add(pool.submit(() -> reader.readPrepared(ACCOUNT)));
            var first = jobs.getFirst().get();
            for (var job : jobs) assertThat(job.get()).isSameAs(first);
        }
        assertThat(db.rawQueries.get()).isEqualTo(1);
        assertThat(db.manifestQueries.get()).isEqualTo(9);
    }

    @Test void repeatableReadDoesNotMixConcurrentReplacementAndNextQuerySeesIt() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1"); db.put(DAY.plusDays(1), 2, "2");
        db.afterRaw = () -> { db.put(DAY.plusDays(1), 3, "9"); db.afterRaw = null; };
        var reader = db.layered(directory);
        var old = reader.read(ACCOUNT);
        assertThat(old.days().getLast().versionId()).isEqualTo(2);
        assertThat(old.facts().getLast().spend()).isEqualByComparingTo("2");
        var next = reader.read(ACCOUNT);
        assertThat(next.days().getLast().versionId()).isEqualTo(3);
        assertThat(next.facts().getLast().spend()).isEqualByComparingTo("9");
        assertThat(reader.counters().rawDaysVerified()).isEqualTo(1);
    }

    @Test void unavailableCacheRootFallsBackWithoutWritingBusinessData() throws Exception {
        Db db = new Db(); db.put(DAY, 1, "1");
        Path invalid = directory.resolve("regular-file"); Files.writeString(invalid, "sentinel");
        var reader = db.layered(invalid);
        assertThat(reader.read(ACCOUNT)).isEqualTo(db.strict.read(ACCOUNT));
        assertThat(reader.counters().forcedAudit()).isTrue();
        assertThat(Files.readString(invalid)).isEqualTo("sentinel");
    }

    private static final class Db {
        final NavigableMap<LocalDate, Map<String,Object>> rows = new TreeMap<>();
        List<Map<String,Object>> transactionRows = List.of();
        final AtomicInteger rawQueries = new AtomicInteger(), manifestQueries = new AtomicInteger();
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final JdbcDashboardSnapshotReader strict = new JdbcDashboardSnapshotReader(source);
        Runnable afterRaw;
        Db() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doAnswer(call -> {
                if (!call.getArgument(0, Boolean.class)) transactionRows = rows.values().stream()
                        .map(row -> (Map<String,Object>) new HashMap<>(row)).toList();
                return null;
            }).when(connection).setAutoCommit(anyBoolean());
            when(connection.prepareStatement(anyString())).thenAnswer(call -> statement(call.getArgument(0)));
        }
        LayeredDashboardReader layered(Path path) { return new LayeredDashboardReader(strict, new DerivedDayCache(path)); }
        void put(LocalDate day, long version, String spend) {
            try {
                String raw = "{\"material_id\":\"demo\",\"stat_time_day\":\"" + day + "\","
                        + "\"roi2_material_video_name\":\"name\",\"material_create_time_v2\":\"" + day.minusDays(1)
                        + "\",\"stat_cost_for_roi2\":" + spend + ",\"total_pay_order_gmv_for_roi2\":3}";
                Map<String,Object> r = new HashMap<>();
                r.put("state_account", ACCOUNT); r.put("state_date", java.sql.Date.valueOf(day));
                r.put("pointer_id", version); r.put("version_id", version); r.put("version_account", ACCOUNT);
                r.put("version_date", java.sql.Date.valueOf(day)); r.put("source_type", "MANUAL_HISTORY");
                r.put("content_hash", sha("[" + raw + "]")); r.put("disposition", "CURRENT"); r.put("row_count", 1);
                r.put("fact_id", version); r.put("fact_version", version); r.put("fact_account", ACCOUNT);
                r.put("fact_date", java.sql.Date.valueOf(day)); r.put("material_id", "demo");
                r.put("source_row_number", 1); r.put("raw_fact_json", raw); r.put("row_sha256", sha(raw));
                r.put("updated_at", Timestamp.valueOf("2026-10-18 00:00:00")); rows.put(day,r);
            } catch (Exception e) { throw new AssertionError(e); }
        }
        PreparedStatement statement(String sql) throws Exception {
            assertThat(sql.stripLeading()).startsWith("SELECT");
            PreparedStatement p = mock(PreparedStatement.class);
            Map<Integer,Object> params = new HashMap<>();
            doAnswer(c -> { params.put(c.getArgument(0), c.getArgument(1)); return null; }).when(p).setString(anyInt(), anyString());
            doAnswer(c -> { params.put(c.getArgument(0), c.getArgument(1)); return null; }).when(p).setDate(anyInt(), any());
            when(p.executeQuery()).thenAnswer(c -> {
                if (sql.contains("@@transaction_read_only")) {
                    ResultSet rs = mock(ResultSet.class); when(rs.next()).thenReturn(true, false); when(rs.getBoolean(1)).thenReturn(true); return rs;
                }
                List<Map<String,Object>> selected = transactionRows;
                if (sql.contains("scsj_material_day_fact")) {
                    rawQueries.incrementAndGet();
                    if (params.containsKey(2)) selected = selected.stream().filter(r -> params.get(2).equals(r.get("state_date"))).toList();
                    if (afterRaw != null) afterRaw.run();
                } else if (sql.contains("LEFT JOIN scsj_fact_input_version")) manifestQueries.incrementAndGet();
                return result(selected);
            });
            return p;
        }
        static ResultSet result(List<Map<String,Object>> rows) throws Exception {
            ResultSet rs = mock(ResultSet.class); AtomicInteger index = new AtomicInteger(-1);
            when(rs.next()).thenAnswer(c -> index.incrementAndGet() < rows.size());
            when(rs.getString(anyString())).thenAnswer(c -> Objects.toString(rows.get(index.get()).get(c.getArgument(0)), null));
            when(rs.getLong(anyString())).thenAnswer(c -> ((Number) rows.get(index.get()).getOrDefault(c.getArgument(0), 0L)).longValue());
            when(rs.getInt(anyString())).thenAnswer(c -> ((Number) rows.get(index.get()).getOrDefault(c.getArgument(0), 0)).intValue());
            when(rs.getDate(anyString())).thenAnswer(c -> rows.get(index.get()).get(c.getArgument(0)));
            when(rs.getObject(anyString())).thenAnswer(c -> rows.get(index.get()).get(c.getArgument(0)));
            when(rs.getObject(anyString(), eq(Long.class))).thenAnswer(c -> rows.get(index.get()).get(c.getArgument(0)));
            when(rs.getTimestamp(anyString(), any(Calendar.class))).thenAnswer(c -> rows.get(index.get()).get(c.getArgument(0)));
            return rs;
        }
    }
    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
