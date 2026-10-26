package cn.datadashboard.query;

import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in, newly initialized port 13480 only; no default/production instance is accessed. */
@EnabledIfEnvironmentVariable(named = "SCSJ_IT_MYSQL_ENABLED", matches = "(?i)true",
        disabledReason = "No explicit synthetic MySQL instance; no database connection attempted")
class D180LayeredMySqlIntegrationTest {
    private static final String A = "synthetic-d180-account-a", B = "synthetic-d180-account-b";
    private static final LocalDate FIRST = LocalDate.of(2025, 11, 1);
    private static final LocalDate LAST = FIRST.plusDays(349);
    private static final JsonMapper JSON = new JsonMapper();
    private static final DashboardCalculator CALCULATOR = new DashboardCalculator(
            Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));
    private static DataSource owner, reader;
    private static String schema, queryUser;
    private static boolean accountCreated;
    private static Path evidence;

    @BeforeAll static void createFreshSyntheticSchemaAndSelectOnlyReader() throws Exception {
        // This test must never accidentally attach to the pre-existing developer instance.
        assertThat(System.getenv("SCSJ_IT_MYSQL_PORT")).isEqualTo("13480");
        String username = System.getenv("SCSJ_IT_MYSQL_USERNAME");
        String password = System.getenv("SCSJ_IT_MYSQL_PASSWORD");
        assertThat(username != null && !username.isBlank() && password != null).isTrue();
        String suffix = UUID.randomUUID().toString().replace("-", "");
        schema = "SCSJ_IT_D180_" + suffix;
        queryUser = "scsj180_" + suffix.substring(0, 16);
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path workspace = Files.isDirectory(cwd.resolve("backend")) ? cwd : cwd.getParent();
        assertThat(workspace != null && Files.isRegularFile(workspace.resolve("START_HERE.md"))).isTrue();
        evidence = workspace.resolve("qa/runs/d180-layered-mysql-" + suffix);
        Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("synthetic-evidence.txt"), "D180 generated non-business fixtures only\n");
        try {
            DataSource admin = dataSource("", username, password);
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            owner = dataSource(schema, username, password);
            Flyway.configure().table("scsj_flyway_schema_history").dataSource(owner).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            // A fresh SELECT-only principal; CREATE USER deliberately lacks IF NOT EXISTS.
            String queryPassword = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = owner.getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE USER '" + queryUser + "'@'127.0.0.1' IDENTIFIED BY '" + queryPassword + "'");
                accountCreated = true;
                s.execute("GRANT SELECT ON `" + schema + "`.* TO '" + queryUser + "'@'127.0.0.1'");
            }
            reader = dataSource(schema, queryUser, queryPassword);
            try (Connection c = owner.getConnection()) {
                c.setAutoCommit(false);
                for (int day = 0; day < 350; day++) insertDay(c, A, FIRST.plusDays(day), 100, 0);
                for (int day = 0; day < 30; day++) insertDay(c, B, LAST.minusDays(29 - day), 100, 0);
                c.commit();
            }
        } catch (Exception failure) {
            // Do not retain a JDBC exception that may include the random temporary credential SQL.
            throw new AssertionError("D180_SYNTHETIC_DATABASE_SETUP_FAILED");
        }
        Files.writeString(evidence.resolve("test-schema.txt"), schema + "\n");
    }

    @AfterAll static void removeOnlyCreatedQueryAccountKeepSchemaAndEvidence() {
        if (accountCreated) {
            try (Connection c = owner.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP USER '" + queryUser + "'@'127.0.0.1'");
                accountCreated = false;
            } catch (Exception failure) { throw new AssertionError("D180_TEMPORARY_QUERY_ACCOUNT_CLEANUP_FAILED"); }
        }
        if (schema != null) System.out.println("D180 synthetic schema retained: " + schema);
    }

    @Test void strictColdWarmRestartIncrementalReplacementEmptyAndConcurrentAccounts() throws Exception {
        Map<String, Object> metrics = new LinkedHashMap<>();
        long heapBefore = usedHeap(), gcBefore = gcMillis();
        var strict = new JdbcDashboardSnapshotReader(reader);
        long start = System.nanoTime();
        DashboardModels.Snapshot baseline = strict.read(A);
        metrics.put("strictMillis", elapsed(start));
        assertThat(baseline.days()).hasSize(350);
        assertThat(baseline.facts()).hasSize(35000);
        var layered = new LayeredDashboardReader(strict, new DerivedDayCache(evidence.resolve("derived")));
        start = System.nanoTime();
        var cold = layered.readPrepared(A);
        metrics.put("coldMillis", elapsed(start));
        assertThat(cold.snapshot()).isEqualTo(baseline);
        assertThat(layered.counters()).isEqualTo(new LayeredDashboardReader.Counters(350, 0, 0, false));
        assertBusinessEqual(baseline, cold, LAST);
        start = System.nanoTime();
        assertThat(layered.readPrepared(A)).isSameAs(cold);
        metrics.put("warmMillis", elapsed(start));
        assertThat(layered.counters()).isEqualTo(new LayeredDashboardReader.Counters(0, 0, 350, false));

        var restart = new LayeredDashboardReader(strict, new DerivedDayCache(evidence.resolve("derived")));
        start = System.nanoTime();
        var restarted = restart.readPrepared(A);
        metrics.put("restartMillis", elapsed(start));
        assertThat(restarted.snapshot()).isEqualTo(baseline);
        assertThat(restart.counters()).isEqualTo(new LayeredDashboardReader.Counters(0, 350, 0, false));
        assertBusinessEqual(baseline, restarted, LAST);

        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false); insertDay(c, A, LAST.plusDays(1), 100, 0); c.commit();
        }
        start = System.nanoTime();
        var appended = restart.readPrepared(A);
        metrics.put("newDayMillis", elapsed(start));
        assertThat(restart.counters()).isEqualTo(new LayeredDashboardReader.Counters(1, 0, 350, false));
        assertThat(appended.snapshot().facts()).hasSize(35100);
        assertBusinessEqual(strict.read(A), appended, LAST.plusDays(1));

        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false); insertDay(c, A, LAST, 100, 7); c.commit();
        }
        var replaced = restart.readPrepared(A);
        assertThat(restart.counters()).isEqualTo(new LayeredDashboardReader.Counters(1, 0, 350, false));
        assertBusinessEqual(strict.read(A), replaced, LAST);
        try (Connection c = owner.getConnection()) {
            c.setAutoCommit(false); insertDay(c, A, LAST.plusDays(2), 0, 0); c.commit();
        }
        var empty = restart.readPrepared(A);
        assertThat(restart.counters()).isEqualTo(new LayeredDashboardReader.Counters(1, 0, 351, false));
        assertThat(empty.snapshot().days()).hasSize(352);
        assertThat(empty.snapshot().facts()).hasSize(35100);
        assertBusinessEqual(strict.read(A), empty, LAST.plusDays(2));
        // An audit always reads raw state, including previous-version metadata, independently.
        var audit = restart.inspect(A, LAST.minusDays(6), LAST.plusDays(2));
        assertThat(audit.validationScope()).isEqualTo("CURRENT_DB_FACT_HASHES");

        // Cold builds of distinct accounts deliberately share a bounded single-account owner.
        var concurrent = new LayeredDashboardReader(strict, new DerivedDayCache(evidence.resolve("concurrent-derived")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            start = System.nanoTime();
            var a = pool.submit(() -> concurrent.readPrepared(A));
            var b = pool.submit(() -> concurrent.readPrepared(B));
            var resultA = a.get(5, TimeUnit.MINUTES); var resultB = b.get(5, TimeUnit.MINUTES);
            metrics.put("twoAccountColdMillis", elapsed(start));
            assertThat(resultA.snapshot().accountId()).isEqualTo(A);
            assertThat(resultB.snapshot().days()).hasSize(30);
            assertThat(resultB.snapshot().facts()).hasSize(3000);
            assertBusinessEqual(strict.read(A), resultA, LAST.plusDays(2));
            assertBusinessEqual(strict.read(B), resultB, LAST);
        }
        // Permission proof: reader is a distinct SELECT-only principal, not the seeding owner.
        try (Connection c = reader.getConnection(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE scsj_fact_day_state SET updated_at=updated_at WHERE 1=0"))
                    .isInstanceOf(SQLException.class).satisfies(f -> assertThat(((SQLException) f).getErrorCode()).isEqualTo(1142));
        }
        try (Connection c = owner.getConnection(); PreparedStatement p = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=?")) {
            p.setString(1, schema.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet r = p.executeQuery()) { assertThat(r.next()).isTrue(); assertThat(r.getInt(1)).isEqualTo(4); }
        }
        metrics.put("syntheticInitialAccounts", 2); metrics.put("syntheticInitialDays", 380);
        metrics.put("syntheticInitialRows", 38000); metrics.put("heapBeforeBytes", heapBefore);
        metrics.put("heapAfterBytes", usedHeap()); metrics.put("gcMillisDelta", gcMillis() - gcBefore);
        metrics.put("productionAccessed", false); metrics.put("cloudPerformanceVerified", false);
        Files.writeString(evidence.resolve("measurements.json"), JSON.writeValueAsString(metrics));
        System.out.println("D180_LAYERED_MYSQL_PASSED " + JSON.writeValueAsString(metrics));
    }

    private static void assertBusinessEqual(DashboardModels.Snapshot strict,
                                            DashboardCalculator.PreparedSnapshot prepared, LocalDate selected) {
        assertThat(prepared.snapshot()).isEqualTo(strict);
        assertThat(CALCULATOR.topPrepared(prepared, "synthetic-project", selected, 10))
                .isEqualTo(CALCULATOR.top(strict, "synthetic-project", selected, 10));
        var current = new DashboardModels.DateRange(selected.minusDays(6), selected);
        var previous = new DashboardModels.DateRange(selected.minusDays(13), selected.minusDays(7));
        assertThat(CALCULATOR.reviewPrepared(prepared, "synthetic-project", current, previous, 10))
                .isEqualTo(CALCULATOR.review(strict, "synthetic-project", current, previous, 10));
    }

    private static void insertDay(Connection c, String account, LocalDate day, int count, int revision) throws Exception {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String spend = i % 19 == 0 ? "0" : (i + revision + 1) + ".00000000000000000001";
            String gmv = i % 17 == 0 ? "null" : Integer.toString((i + revision + 1) * 3);
            String name = (i % 11 == 0 && day.isBefore(FIRST.plusDays(150)) ? "ZZ-" : "fixture-") + i;
            raw.add("{\"material_id\":\"m" + i + "\",\"stat_time_day\":\"" + day
                    + "\",\"roi2_material_video_name\":\"" + name + "\",\"material_create_time_v2\":\"" + FIRST
                    + "\",\"stat_cost_for_roi2\":" + spend + ",\"total_pay_order_gmv_for_roi2\":" + gmv
                    + ",\"live_show_count_for_roi2_v2\":1000,\"live_watch_count_for_roi2_v2\":100,\"total_pay_order_count_for_roi2\":3}");
        }
        String contentHash = sha("[" + String.join(",", raw) + "]"), evidenceHash = sha("synthetic-only");
        Long previous = null;
        try (PreparedStatement p = c.prepareStatement("SELECT current_input_version_id FROM scsj_fact_day_state WHERE account_id=? AND stat_date=?")) {
            p.setString(1, account); p.setDate(2, java.sql.Date.valueOf(day));
            try (ResultSet r = p.executeQuery()) { if (r.next()) previous = r.getLong(1); }
        }
        if (previous != null) {
            try (PreparedStatement p = c.prepareStatement("UPDATE scsj_fact_input_version SET disposition='PREVIOUS' WHERE id=?")) {
                p.setLong(1, previous); assertThat(p.executeUpdate()).isEqualTo(1);
            }
        }
        long version;
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO scsj_fact_input_version
                (account_id,stat_date,project_ref,source_type,data_version,content_hash,schema_version,catalog_version,
                 manifest_path,manifest_size_bytes,manifest_sha256,material_path,material_size_bytes,material_sha256,
                 disposition,row_count,run_id)
                VALUES (?,?,'synthetic-project','MANUAL_HISTORY',?,?,'synthetic-v1','synthetic-v1',?,1,?,?,1,?,'CURRENT',?,'synthetic-run')
                """, Statement.RETURN_GENERATED_KEYS)) {
            String path = evidence.resolve("synthetic-evidence.txt").toString();
            p.setString(1, account); p.setDate(2, java.sql.Date.valueOf(day)); p.setString(3, "revision-" + revision);
            p.setString(4, contentHash); p.setString(5, path); p.setString(6, evidenceHash);
            p.setString(7, path); p.setString(8, evidenceHash); p.setInt(9, count); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { assertThat(r.next()).isTrue(); version = r.getLong(1); }
        }
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO scsj_material_day_fact
                (input_version_id,account_id,stat_date,material_id,source_row_number,raw_fact_json,row_sha256)
                VALUES (?,?,?,?,?,?,?)
                """)) {
            for (int i = 0; i < raw.size(); i++) {
                p.setLong(1, version); p.setString(2, account); p.setDate(3, java.sql.Date.valueOf(day));
                p.setString(4, "m" + i); p.setInt(5, i + 1); p.setString(6, raw.get(i)); p.setString(7, sha(raw.get(i))); p.addBatch();
            }
            if (!raw.isEmpty()) p.executeBatch();
        }
        if (previous == null) {
            try (PreparedStatement p = c.prepareStatement("INSERT INTO scsj_fact_day_state(account_id,stat_date,current_input_version_id) VALUES (?,?,?)")) {
                p.setString(1, account); p.setDate(2, java.sql.Date.valueOf(day)); p.setLong(3, version); p.executeUpdate();
            }
        } else {
            try (PreparedStatement p = c.prepareStatement("UPDATE scsj_fact_day_state SET previous_input_version_id=current_input_version_id,current_input_version_id=? WHERE account_id=? AND stat_date=?")) {
                p.setLong(1, version); p.setString(2, account); p.setDate(3, java.sql.Date.valueOf(day)); p.executeUpdate();
            }
        }
    }

    private static DataSource dataSource(String database, String user, String password) {
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13480/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&rewriteBatchedStatements=true", user, password);
    }
    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private static long usedHeap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum(); }
}
