package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Optional real-MySQL checks. Only explicitly enabled loopback instances are used.
 * Configuration: SCSJ_IT_MYSQL_ENABLED=true, SCSJ_IT_MYSQL_PORT,
 * SCSJ_IT_MYSQL_USERNAME and SCSJ_IT_MYSQL_PASSWORD (empty is permitted locally).
 * A fresh SCSJ_IT_* schema and synthetic evidence under qa/runs are retained for review.
 */
@EnabledIfEnvironmentVariable(named = "SCSJ_IT_MYSQL_ENABLED", matches = "(?i)true",
        disabledReason = "Real MySQL integration is not configured; no database connection attempted")
class MysqlFactImportIntegrationTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 11);
    private static final List<String> TABLES = List.of(
            "scsj_fact_input_version", "scsj_material_day_fact", "scsj_fact_day_state", "scsj_flyway_schema_history");
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static FactImportStore store;
    private static String schema;
    private static Path evidenceRoot;

    @BeforeAll
    static void createIsolatedSchemaAndProductionTransactionProxy() throws Exception {
        String portText = System.getenv("SCSJ_IT_MYSQL_PORT");
        assertThat(portText).as("Set an explicit local test port").matches("[0-9]{4,5}");
        int port = Integer.parseInt(portText);
        assertThat(port).isBetween(1024, 65535);
        String username = System.getenv("SCSJ_IT_MYSQL_USERNAME");
        if (username == null || username.isBlank()) {
            throw new AssertionError("Local MySQL integration username is not configured");
        }
        String password = System.getenv("SCSJ_IT_MYSQL_PASSWORD");
        if (password == null) {
            throw new AssertionError("Local MySQL integration password variable is not configured");
        }

        String runSuffix = UUID.randomUUID().toString().replace("-", "");
        schema = "SCSJ_IT_" + runSuffix;
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path workspace = Files.isDirectory(workingDirectory.resolve("backend"))
                ? workingDirectory : workingDirectory.getParent();
        if (workspace == null || !Files.isRegularFile(workspace.resolve("START_HERE.md"))
                || !Files.isRegularFile(workspace.resolve("backend/pom.xml"))) {
            throw new AssertionError("Run this test from the project root or backend directory");
        }
        evidenceRoot = workspace.resolve("qa/runs/mysql-fact-import-integration-" + runSuffix);
        Files.createDirectories(evidenceRoot);

        try {
            DriverManagerDataSource admin = dataSource(port, "", username, password);
            // No IF NOT EXISTS: an existing database must never be reused accidentally.
            new JdbcTemplate(admin).execute("CREATE DATABASE `" + schema
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            DataSource dataSource = dataSource(port, schema, username, password);
            Flyway.configure().table("scsj_flyway_schema_history").dataSource(dataSource).locations("classpath:db/migration")
                    .cleanDisabled(true).load().migrate();
            context = new AnnotationConfigApplicationContext();
            context.registerBean(DataSource.class, () -> dataSource);
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
            context.registerBean(DataSourceTransactionManager.class,
                    () -> new DataSourceTransactionManager(dataSource));
            context.registerBean(VersionPlacementPolicy.class, VersionPlacementPolicy::new);
            context.registerBean(StoredFactVersionVerifier.class, StoredFactVersionVerifier::new);
            context.registerBean(JdbcFactImportStore.class, () -> new JdbcFactImportStore(
                    context.getBean(JdbcTemplate.class), context.getBean(VersionPlacementPolicy.class),
                    context.getBean(StoredFactVersionVerifier.class)));
            context.register(TransactionConfiguration.class);
            context.refresh();
            jdbc = context.getBean(JdbcTemplate.class);
            store = context.getBean(FactImportStore.class);
        } catch (RuntimeException exception) {
            // JDBC authentication errors can contain usernames. Do not retain their text or cause.
            throw new AssertionError("Isolated local MySQL initialization failed; check the local instance and permissions");
        }
        assertThat(AopUtils.isAopProxy(store)).as("Use the production @Transactional boundary").isTrue();
        Files.writeString(evidenceRoot.resolve("test-schema.txt"), schema + System.lineSeparator(),
                StandardCharsets.UTF_8);
        System.out.println("Synthetic MySQL integration schema retained: " + schema);
    }

    @AfterAll
    static void closeContextWithoutDeletingReviewEvidence() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void migratedTablesHaveCommentsEnumsAndAutomaticTimestamps() {
        List<String> tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND LEFT(LOWER(TABLE_NAME), 5) = 'scsj_'
                """, String.class, schema);
        assertThat(tables.stream().map(String::toLowerCase)).containsExactlyInAnyOrderElementsOf(TABLES);
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT TABLE_COMMENT FROM information_schema.TABLES
                    WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND LOWER(TABLE_NAME) = ?
                    """, String.class, schema, table)).isNotBlank();
            List<Map<String, Object>> columns = jdbc.queryForList("""
                    SELECT COLUMN_NAME, COLUMN_COMMENT, IS_NULLABLE, COLUMN_DEFAULT, EXTRA
                    FROM information_schema.COLUMNS WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND LOWER(TABLE_NAME) = ?
                    """, schema, table);
            assertThat(columns).isNotEmpty();
            for (Map<String, Object> column : columns) {
                assertThat(column.get("COLUMN_COMMENT").toString())
                        .as("Comment for %s.%s", table, column.get("COLUMN_NAME")).isNotBlank();
            }
            for (String timestamp : List.of("created_at", "updated_at")) {
                Map<String, Object> column = columns.stream()
                        .filter(value -> timestamp.equals(value.get("COLUMN_NAME"))).findFirst().orElseThrow();
                assertThat(column.get("IS_NULLABLE")).isEqualTo("NO");
                assertThat(column.get("COLUMN_DEFAULT").toString().toLowerCase()).contains("current_timestamp");
                if (timestamp.equals("updated_at")) {
                    assertThat(column.get("EXTRA").toString().toLowerCase()).contains("on update current_timestamp");
                }
            }
        }
        assertThat(columnComment("source_type"))
                .contains("MCP_AUTO=自动MCP采集", "MANUAL_HISTORY=人工历史原件");
        assertThat(columnComment("disposition"))
                .contains("CURRENT=当前事实", "PREVIOUS=上一回滚版", "ISOLATED=第三种及以后不同内容");
    }

    @Test
    void firstImportCommitsEveryRowAndPreservesZeroNullUnknownAndUnicode() throws Exception {
        PreparedFactBatch batch = batch("1000000000000001", "first", 0, false);
        FactStoreResult result = store.importAtomically(batch);

        assertThat(result.outcome()).isEqualTo(FactStoreOutcome.INSERTED);
        assertThat(result.disposition()).isEqualTo(FactVersionDisposition.CURRENT);
        assertVersionAndRows(batch, result.inputVersionId());
        assertPointers(batch.accountId(), result.inputVersionId(), null);
        JsonNode first = MAPPER.readTree(jdbc.queryForObject("""
                SELECT raw_fact_json FROM scsj_material_day_fact
                WHERE input_version_id = ? AND source_row_number = 1
                """, String.class, result.inputVersionId()));
        assertThat(first.get("zero_metric").intValue()).isZero();
        assertThat(first.get("nullable_metric").isNull()).isTrue();
        assertThat(first.get("unknown_metric").stringValue()).isEqualTo("UNKNOWN");
        assertThat(first.get("material_name").stringValue()).isEqualTo("合成测试素材🌈");
    }

    @Test
    void equivalentRowsWithDifferentFileBytesRemainIdempotentWithoutReplacingEvidence() throws Exception {
        PreparedFactBatch first = batch("1000000000000002", "original", 7, false);
        PreparedFactBatch reformatted = batch(first.accountId(), "reformatted", 7, true);
        assertThat(reformatted.contentHash()).isEqualTo(first.contentHash());
        assertThat(reformatted.materialEvidence().sha256()).isNotEqualTo(first.materialEvidence().sha256());
        assertThat(reformatted.manifestEvidence().sha256()).isNotEqualTo(first.manifestEvidence().sha256());
        FactStoreResult original = store.importAtomically(first);
        FactStoreResult replay = store.importAtomically(reformatted);

        assertThat(replay.outcome()).isEqualTo(FactStoreOutcome.IDEMPOTENT);
        assertThat(replay.inputVersionId()).isEqualTo(original.inputVersionId());
        assertThat(count("scsj_fact_input_version", first.accountId())).isEqualTo(1);
        assertThat(count("scsj_material_day_fact", first.accountId())).isEqualTo(2);
        assertVersionAndRows(first, original.inputVersionId());
    }

    @Test
    void secondDifferentVersionActivatesAndThirdRemainsIsolated() throws Exception {
        PreparedFactBatch first = batch("1000000000000003", "version-1", 1, false);
        PreparedFactBatch second = batch(first.accountId(), "version-2", 2, false);
        PreparedFactBatch third = batch(first.accountId(), "version-3", 3, false);
        FactStoreResult one = store.importAtomically(first);
        FactStoreResult two = store.importAtomically(second);
        FactStoreResult three = store.importAtomically(third);

        assertThat(disposition(one.inputVersionId())).isEqualTo("PREVIOUS");
        assertThat(disposition(two.inputVersionId())).isEqualTo("CURRENT");
        assertThat(three.disposition()).isEqualTo(FactVersionDisposition.ISOLATED);
        assertThat(disposition(three.inputVersionId())).isEqualTo("ISOLATED");
        assertPointers(first.accountId(), two.inputVersionId(), one.inputVersionId());
        assertVersionAndRows(first, one.inputVersionId());
        assertVersionAndRows(second, two.inputVersionId());
        assertVersionAndRows(third, three.inputVersionId());
        assertThat(store.importAtomically(first).outcome()).isEqualTo(FactStoreOutcome.IDEMPOTENT);
        assertThat(store.importAtomically(third).outcome()).isEqualTo(FactStoreOutcome.IDEMPOTENT);
        assertPointers(first.accountId(), two.inputVersionId(), one.inputVersionId());
    }

    @Test
    void rowReadbackMismatchRollsBackVersionFactsAndNewDayLockRow() throws Exception {
        PreparedFactBatch valid = batch("1000000000000004", "readback-failure", 5, false);
        List<PreparedMaterialFact> rows = new ArrayList<>(valid.rows());
        PreparedMaterialFact first = rows.getFirst();
        rows.set(0, new PreparedMaterialFact(first.materialId(), first.sourceRowNumber(),
                first.rawFactJson(), "0".repeat(64)));
        PreparedFactBatch invalid = new PreparedFactBatch(valid.runId(), valid.projectRef(), valid.accountId(),
                valid.statDate(), valid.sourceType(), valid.dataVersion(), valid.contentHash(),
                valid.schemaVersion(), valid.catalogVersion(), valid.manifestEvidence(), valid.materialEvidence(), rows);

        assertThatThrownBy(() -> store.importAtomically(invalid)).isInstanceOf(ReadbackVerificationException.class);
        for (String table : TABLES.stream().filter(t -> !t.equals("scsj_flyway_schema_history")).toList()) {
            assertThat(count(table, valid.accountId())).as("Rollback removes writes in %s", table).isZero();
        }
    }

    @Test
    void databaseFailureAfterPreviousDispositionUpdateRestoresOriginalActiveVersion() throws Exception {
        PreparedFactBatch first = batch("1000000000000005", "rollback-original", 1, false);
        PreparedFactBatch second = batch(first.accountId(), "rollback-second", 2, false);
        FactStoreResult original = store.importAtomically(first);
        jdbc.execute("""
                CREATE TRIGGER SCSJ_IT_REJECT_POINTER_UPDATE BEFORE UPDATE ON scsj_fact_day_state
                FOR EACH ROW BEGIN
                    IF NEW.account_id = '1000000000000005'
                        AND NOT (NEW.current_input_version_id <=> OLD.current_input_version_id) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic integration rollback';
                    END IF;
                END
                """);
        try {
            assertThatThrownBy(() -> store.importAtomically(second)).isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER SCSJ_IT_REJECT_POINTER_UPDATE");
        }

        assertThat(count("scsj_fact_input_version", first.accountId())).isEqualTo(1);
        assertThat(count("scsj_material_day_fact", first.accountId())).isEqualTo(2);
        assertThat(count("scsj_fact_day_state", first.accountId())).isEqualTo(1);
        assertThat(disposition(original.inputVersionId())).isEqualTo("CURRENT");
        assertPointers(first.accountId(), original.inputVersionId(), null);
        assertVersionAndRows(first, original.inputVersionId());
    }

    private static DriverManagerDataSource dataSource(int port, String database, String username, String password) {
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&connectTimeout=5000&socketTimeout=15000&allowMultiQueries=false";
        return new DriverManagerDataSource(url, username, password);
    }

    private static PreparedFactBatch batch(String account, String label, int metric, boolean formatted) throws Exception {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int index = 1; index <= 2; index++) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("material_id", "synthetic-material-" + index);
            row.put("stat_time_day", STAT_DATE.toString());
            row.put("material_name", "合成测试素材🌈");
            row.put("metric", metric + index);
            row.put("zero_metric", 0);
            row.put("unknown_metric", "UNKNOWN");
            row.putNull("nullable_metric");
            rows.add(row);
        }
        byte[] material = formatted ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows)
                : MAPPER.writeValueAsBytes(rows);
        byte[] manifest = ("{\"syntheticEvidence\":\"" + label + "\"}").getBytes(StandardCharsets.UTF_8);
        Path directory = evidenceRoot.resolve(account + "-" + label);
        Files.createDirectories(directory);
        Files.write(directory.resolve("synthetic-manifest.json"), manifest);
        Files.write(directory.resolve("synthetic-rows.json"), material);
        List<PreparedMaterialFact> facts = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            String raw = formatted ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows.get(index))
                    : MAPPER.writeValueAsString(rows.get(index));
            facts.add(new PreparedMaterialFact(rows.get(index).get("material_id").stringValue(),
                    index + 1, raw, hash(raw.getBytes(StandardCharsets.UTF_8))));
        }
        return new PreparedFactBatch("synthetic-mysql-" + label, "synthetic-project", account, STAT_DATE,
                FactSourceType.MCP_AUTO, label, hash(MAPPER.writeValueAsBytes(sorted(rows))),
                "synthetic-schema-v1", "synthetic-catalog-v1",
                evidence(directory.resolve("synthetic-manifest.json"), manifest),
                evidence(directory.resolve("synthetic-rows.json"), material), facts);
    }

    private static void assertVersionAndRows(PreparedFactBatch batch, long versionId) {
        Map<String, Object> version = jdbc.queryForMap("""
                SELECT account_id, stat_date, content_hash, source_type, row_count,
                       manifest_path, manifest_size_bytes, manifest_sha256,
                       material_path, material_size_bytes, material_sha256, created_at, updated_at
                FROM scsj_fact_input_version WHERE id = ?
                """, versionId);
        assertThat(version.get("account_id")).isEqualTo(batch.accountId());
        assertThat(version.get("stat_date").toString()).isEqualTo(batch.statDate().toString());
        assertThat(version.get("content_hash")).isEqualTo(batch.contentHash());
        assertThat(version.get("source_type")).isEqualTo(batch.sourceType().name());
        assertThat(((Number) version.get("row_count")).intValue()).isEqualTo(batch.rows().size());
        for (Map.Entry<String, InputFileEvidence> item : Map.of(
                "manifest", batch.manifestEvidence(), "material", batch.materialEvidence()).entrySet()) {
            assertThat(version.get(item.getKey() + "_path")).isEqualTo(item.getValue().absolutePath());
            assertThat(((Number) version.get(item.getKey() + "_size_bytes")).longValue())
                    .isEqualTo(item.getValue().sizeBytes());
            assertThat(version.get(item.getKey() + "_sha256")).isEqualTo(item.getValue().sha256());
        }
        assertThat(version.get("created_at")).isNotNull();
        assertThat(version.get("updated_at")).isNotNull();
        List<Map<String, Object>> facts = jdbc.queryForList("""
                SELECT account_id, stat_date, material_id, source_row_number, raw_fact_json,
                       row_sha256, created_at, updated_at FROM scsj_material_day_fact
                WHERE input_version_id = ? ORDER BY source_row_number
                """, versionId);
        assertThat(facts).hasSize(batch.rows().size());
        for (int index = 0; index < facts.size(); index++) {
            Map<String, Object> actual = facts.get(index);
            PreparedMaterialFact expected = batch.rows().get(index);
            assertThat(actual.get("account_id")).isEqualTo(batch.accountId());
            assertThat(actual.get("stat_date").toString()).isEqualTo(batch.statDate().toString());
            assertThat(actual.get("material_id")).isEqualTo(expected.materialId());
            assertThat(((Number) actual.get("source_row_number")).intValue()).isEqualTo(expected.sourceRowNumber());
            assertThat(actual.get("raw_fact_json")).isEqualTo(expected.rawFactJson());
            assertThat(actual.get("row_sha256")).isEqualTo(expected.rowSha256());
            assertThat(actual.get("created_at")).isNotNull();
            assertThat(actual.get("updated_at")).isNotNull();
        }
    }

    private static void assertPointers(String account, Long current, Long previous) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT current_input_version_id, previous_input_version_id, created_at, updated_at
                FROM scsj_fact_day_state WHERE account_id = ? AND stat_date = ?
                """, account, Date.valueOf(STAT_DATE));
        assertThat(nullableLong(row.get("current_input_version_id"))).isEqualTo(current);
        assertThat(nullableLong(row.get("previous_input_version_id"))).isEqualTo(previous);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static int count(String table, String account) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Unknown synthetic test table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?",
                Integer.class, account);
    }

    private static String disposition(long id) {
        return jdbc.queryForObject("SELECT disposition FROM scsj_fact_input_version WHERE id = ?", String.class, id);
    }

    private static String columnComment(String column) {
        return jdbc.queryForObject("""
                SELECT COLUMN_COMMENT FROM information_schema.COLUMNS
                WHERE LOWER(TABLE_SCHEMA) = LOWER(?) AND LOWER(TABLE_NAME) = 'scsj_fact_input_version' AND COLUMN_NAME = ?
                """, String.class, schema, column);
    }

    private static InputFileEvidence evidence(Path path, byte[] bytes) throws Exception {
        return new InputFileEvidence(path.getFileName().toString(), path.toAbsolutePath().normalize().toString(),
                bytes.length, hash(bytes));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> result.set(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            ((ArrayNode) node).elements().forEach(value -> result.add(sorted(value)));
            return result;
        }
        return node.deepCopy();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfiguration {
    }
}
