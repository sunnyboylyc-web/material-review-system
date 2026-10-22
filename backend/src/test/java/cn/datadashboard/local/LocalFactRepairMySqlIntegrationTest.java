package cn.datadashboard.local;

import cn.datadashboard.ingest.validation.AutomaticFactFixtures;

import cn.datadashboard.operations.OperationsStatus;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Explicitly enabled loopback-only integration of the repair CLI, never an existing fact schema.
 * Synthetic schema and evidence are retained. Credentials come from the test environment only,
 * are used privately under .local, and are never included in assertions or receipts.
 */
@EnabledIfEnvironmentVariable(named = "SCSJ_IT_MYSQL_ENABLED", matches = "(?i)true",
        disabledReason = "Isolated repair MySQL integration is not enabled; no database connection attempted")
class LocalFactRepairMySqlIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT = "1000000000000001";
    private static final String OTHER_ACCOUNT = "1000000000000002";
    private static Path workspace;
    private static Path fixtureRoot;
    private static Path credentials;
    private static String schema;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ObjectNode receiptIndex;
    private String currentCase;

    @BeforeAll
    static void createFreshSyntheticSchemaOnlyInsideExplicitProjectInstance() throws Exception {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext logs) {
            logs.reset();
            logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
        java.util.logging.LogManager.getLogManager().reset();
        Path current = Path.of("").toRealPath();
        workspace = Files.isDirectory(current.resolve("backend")) ? current : current.getParent();
        require(workspace != null && Files.isRegularFile(workspace.resolve("START_HERE.md")), "PROJECT_ROOT_REQUIRED");
        Path local = workspace.resolve(".local").toRealPath();
        require(local.startsWith(workspace) && !local.equals(workspace), "LOCAL_ROOT_REQUIRED");
        String portText = System.getenv("SCSJ_IT_MYSQL_PORT");
        require(portText != null && portText.matches("[0-9]{4,5}"), "EXPLICIT_LOCAL_TEST_PORT_REQUIRED");
        int port = Integer.parseInt(portText);
        require(port >= 1024 && port <= 65535, "LOCAL_TEST_PORT_INVALID");
        String username = System.getenv("SCSJ_IT_MYSQL_USERNAME");
        String password = System.getenv("SCSJ_IT_MYSQL_PASSWORD");
        require(username != null && !username.isBlank() && password != null, "TEST_CREDENTIALS_ENV_REQUIRED");
        String suffix = UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        schema = "SCSJ_LOCAL_REPAIR_IT_" + suffix;
        require(schema.matches("SCSJ_LOCAL_REPAIR_IT_[A-F0-9]{32}"), "FRESH_SCHEMA_NAME_REQUIRED");
        fixtureRoot = local.resolve("repair-integration").resolve(suffix);
        Files.createDirectories(fixtureRoot);
        require(fixtureRoot.toRealPath().startsWith(local), "LOCAL_FIXTURE_ROOT_REQUIRED");
        credentials = fixtureRoot.resolve("credentials.json");
        JSON.writeValue(credentials, JSON.createObjectNode().put("username", username).put("password", password).put("port", port));
        try {
            var admin = new JdbcTemplate(source(port, "", username, password));
            Path datadir = LocalFactRepairMain.decodeDataDirectory(admin.queryForObject("SELECT HEX(@@datadir)", String.class)).toRealPath();
            require(datadir.startsWith(local) && !datadir.equals(local), "PROJECT_LOCAL_DATADIR_REQUIRED");
            // No IF NOT EXISTS and no DROP: collisions fail, existing schemas are never reused.
            admin.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            dataSource = source(port, schema, username, password);
            Flyway.configure().table("scsj_flyway_schema_history").dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
            jdbc = new JdbcTemplate(dataSource);
        } catch (Exception ignored) {
            throw new AssertionError("ISOLATED_REPAIR_MYSQL_SETUP_FAILED");
        }
        Files.writeString(fixtureRoot.resolve("test-schema.txt"), schema + System.lineSeparator(), StandardCharsets.UTF_8);
        receiptIndex = JSON.createObjectNode().put("schemaVersion", "synthetic-repair-integration-index-1.0.0")
                .put("schema", schema).put("syntheticDataOnly", true).put("schemaRetained", true);
        receiptIndex.putArray("receipts");
        JSON.writeValue(fixtureRoot.resolve("receipt-index.json"), receiptIndex);
        System.out.println("Synthetic single-day repair schema retained: " + schema);
        System.out.println("Synthetic repair receipt index: " + fixtureRoot.resolve("receipt-index.json"));
    }

    @BeforeEach
    void selectCaseLabel(TestInfo info) {
        currentCase = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("UNKNOWN_TEST_CASE");
    }

    @Test
    void missingDayBecomesVerifiedAndOperationsUpdatesWithoutTouchingOtherDayOrAccount() throws Exception {
        LocalDate date = LocalDate.of(2026, 2, 1);
        Fixture nextDay = automatic(ACCOUNT, date.plusDays(1), 31, false);
        Fixture otherAccount = automatic(OTHER_ACCOUNT, date, 47, false);
        assertStatus(apply(nextDay, "MISSING"), "CURRENT_VERIFIED");
        assertStatus(apply(otherAccount, "MISSING"), "CURRENT_VERIFIED");
        List<List<Map<String, Object>>> unchanged = otherUnits(ACCOUNT, date);
        Fixture selected = automatic(ACCOUNT, date, 12, false);
        assertThat(operation(selected).state()).isEqualTo("MISSING");
        JsonNode receipt = apply(selected, "MISSING");
        assertStatus(receipt, "CURRENT_VERIFIED");
        assertThat(receipt.get("disposition").stringValue()).isEqualTo("CURRENT");
        assertThat(receipt.get("rowCount").intValue()).isEqualTo(2);
        assertIndependentCurrent(selected);
        OperationsStatus.Day after = operation(selected);
        assertThat(after.state()).isEqualTo("AVAILABLE");
        assertThat(after.currentVersion().contentHash()).isEqualTo(selected.hash);
        assertThat(after.currentVersion().rowCount()).isEqualTo(2);
        assertThat(otherUnits(ACCOUNT, date)).isEqualTo(unchanged);
    }

    @Test
    void repeatedSecondThirdAndPreviousContentPreserveVersionPolicy() throws Exception {
        LocalDate day = LocalDate.of(2026, 3, 1);
        Fixture first = automatic(ACCOUNT, day, 10, false);
        Fixture second = automatic(ACCOUNT, day, 20, false);
        Fixture third = automatic(ACCOUNT, day, 30, false);
        JsonNode firstReceipt = apply(first, "MISSING");
        assertStatus(firstReceipt, "CURRENT_VERIFIED");
        long firstId = firstReceipt.get("inputVersionId").longValue();
        JsonNode repeat = apply(first, first.hash);
        assertStatus(repeat, "CURRENT_VERIFIED");
        assertThat(repeat.get("transactionOutcome").stringValue()).isEqualTo("IDEMPOTENT");
        assertThat(repeat.get("inputVersionId").longValue()).isEqualTo(firstId);
        assertThat(versionCount(first)).isEqualTo(1);
        JsonNode secondReceipt = apply(second, first.hash);
        assertStatus(secondReceipt, "CURRENT_VERIFIED");
        assertThat(versionCount(first)).isEqualTo(2);
        assertThat(operation(second).previousVersionId()).isEqualTo(firstId);
        assertIndependentCurrent(second);
        JsonNode isolated = apply(third, second.hash);
        assertStatus(isolated, "STORED_NOT_CURRENT");
        assertThat(isolated.get("disposition").stringValue()).isEqualTo("ISOLATED");
        assertThat(versionCount(first)).isEqualTo(3);
        assertThat(operation(second).isolatedVersionCount()).isEqualTo(1);
        JsonNode oldContent = apply(first, second.hash);
        assertStatus(oldContent, "STORED_NOT_CURRENT");
        assertThat(oldContent.get("disposition").stringValue()).isEqualTo("PREVIOUS");
        assertThat(oldContent.get("transactionOutcome").stringValue()).isEqualTo("IDEMPOTENT");
        assertThat(versionCount(first)).isEqualTo(3);
        assertIndependentCurrent(second);
    }

    @Test
    void staleCurrentExpectationRejectsWithoutAnyVersionOrUnrelatedChange() throws Exception {
        LocalDate day = LocalDate.of(2026, 4, 1);
        Fixture first = automatic(ACCOUNT, day, 10, false);
        Fixture next = automatic(ACCOUNT, day, 20, false);
        assertStatus(apply(first, "MISSING"), "CURRENT_VERIFIED");
        List<List<Map<String, Object>>> before = allFactTables();
        assertStatus(apply(next, "MISSING"), "REJECTED");
        assertThat(allFactTables()).isEqualTo(before);
        assertStatus(apply(next, "a".repeat(64)), "REJECTED");
        assertThat(allFactTables()).isEqualTo(before);
        assertIndependentCurrent(first);
    }

    @Test
    void changedSourceConfigOrPreflightCannotExecuteAndUnknownDayCannotBroadenScope() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            Fixture fixture = automatic(ACCOUNT, LocalDate.of(2026, 5, 1).plusDays(scenario), 10, false);
            String[] args = arguments(fixture, "MISSING");
            if (scenario == 0) {
                ObjectNode source = (ObjectNode) JSON.readTree(Files.readAllBytes(fixture.input.resolve("material_day.json")));
                ((ObjectNode) source.get("rows").get(0)).put("stat_cost_for_roi2", 99);
                JSON.writeValue(fixture.input.resolve("material_day.json"), source);
            } else if (scenario == 1) {
                ObjectNode config = (ObjectNode) JSON.readTree(Files.readAllBytes(fixture.config));
                config.put("runId", "changed-after-preflight");
                JSON.writeValue(fixture.config, config);
            } else if (scenario == 2) {
                args[3] = "a".repeat(64);
            } else if (scenario == 3) {
                args[1] = fixture.date.plusDays(20).toString();
            } else {
                args[5] = "--not-apply";
            }
            List<List<Map<String, Object>>> before = allFactTables();
            if (scenario == 4) {
                assertThatThrownBy(() -> LocalFactRepairMain.execute(args, workspace, LocalFactRepairMain.Hooks.NONE))
                        .isInstanceOf(LocalFactRepairMain.Rejection.class).hasMessage("EXPLICIT_SINGLE_DAY_APPLY_REQUIRED");
            } else {
                JsonNode receipt = execute(args, LocalFactRepairMain.Hooks.NONE);
                assertStatus(receipt, "REJECTED");
            }
            assertThat(versionCount(fixture)).isZero();
            assertThat(allFactTables()).isEqualTo(before);
        }
    }

    @Test
    void confirmedManualEmptyDayIsCompleteZeroRowsNotMissing() throws Exception {
        Fixture empty = manualEmpty(ACCOUNT, LocalDate.of(2026, 6, 1));
        assertThat(operation(empty).state()).isEqualTo("MISSING");
        JsonNode receipt = apply(empty, "MISSING");
        assertStatus(receipt, "CURRENT_VERIFIED");
        assertThat(receipt.get("rowCount").intValue()).isZero();
        OperationsStatus.Day after = operation(empty);
        assertThat(after.state()).isEqualTo("EMPTY");
        assertThat(after.currentVersion().rowCount()).isZero();
        assertThat(after.currentVersion().sourceType()).isEqualTo("MANUAL_HISTORY");
        assertIndependentCurrent(empty);
    }

    @Test
    void independentVerificationFailureCannotClaimSuccessOrRollback() throws Exception {
        Fixture fixture = automatic(ACCOUNT, LocalDate.of(2026, 7, 1), 10, false);
        JsonNode receipt = execute(arguments(fixture, "MISSING"), new LocalFactRepairMain.Hooks() {
            @Override public void beforeIndependentVerification() {
                throw new IllegalStateException("SYNTHETIC_POSTCOMMIT_CHECK_FAILURE");
            }
        });
        assertStatus(receipt, "COMMITTED_UNVERIFIED");
        assertThat(receipt.get("inputVersionId").isNull()).isFalse();
        assertThat(receipt.get("transactionOutcome").isNull()).isFalse();
        // This is a new independent SQL/read-only query, not the failed injected verification.
        assertIndependentCurrent(fixture);
        assertThat(operation(fixture).state()).isEqualTo("AVAILABLE");
    }

    @Test
    void uncertainTransactionCompletionIsNeverReportedAsVerifiedOrAutomaticRetry() throws Exception {
        Fixture fixture = automatic(ACCOUNT, LocalDate.of(2026, 8, 1), 10, false);
        List<List<Map<String, Object>>> before = allFactTables();
        JsonNode receipt = execute(arguments(fixture, "MISSING"), new LocalFactRepairMain.Hooks() {
            @Override public void beforeTransactionCompletion() {
                throw new IllegalStateException("SYNTHETIC_COMPLETION_FAILURE");
            }
        });
        assertStatus(receipt, "EXECUTION_UNCERTAIN");
        // Test-only SQL establishes this injected case rolled back; the CLI must remain conservative.
        assertThat(allFactTables()).isEqualTo(before);
    }

    private JsonNode apply(Fixture fixture, String expected) throws Exception {
        return execute(arguments(fixture, expected), LocalFactRepairMain.Hooks.NONE);
    }

    private static String[] arguments(Fixture fixture, String expected) throws Exception {
        var preflight = LocalFactPreflightMain.execute(fixture.config, fixture.date.toString(), workspace);
        require(preflight.status() == LocalFactPreflightMain.Status.SOURCE_READY, "SYNTHETIC_PREFLIGHT_NOT_READY");
        return new String[]{fixture.config.toString(), fixture.date.toString(), preflight.receiptPath().toString(),
                sha(Files.readAllBytes(preflight.receiptPath())), expected, "--apply"};
    }

    private JsonNode execute(String[] args, LocalFactRepairMain.Hooks hooks) throws Exception {
        var result = LocalFactRepairMain.execute(args, workspace, hooks);
        byte[] receiptBytes = Files.readAllBytes(result.receiptPath());
        JsonNode receipt = JSON.readTree(receiptBytes);
        receiptIndex.withArray("receipts").addObject().put("case", currentCase).put("status", result.status().name())
                .put("statDate", args[1]).put("absolutePath", result.receiptPath().toString())
                .put("sizeBytes", receiptBytes.length).put("sha256", sha(receiptBytes));
        JSON.writeValue(fixtureRoot.resolve("receipt-index.json"), receiptIndex);
        assertThat(receipt.get("status").stringValue()).isEqualTo(result.status().name());
        assertThat(receipt.toString()).doesNotContain(ACCOUNT, OTHER_ACCOUNT, "synthetic-material-private",
                "SYNTHETIC_POSTCOMMIT_CHECK_FAILURE", "SYNTHETIC_COMPLETION_FAILURE");
        return receipt;
    }

    private static void assertStatus(JsonNode receipt, String status) {
        assertThat(receipt.get("status").stringValue()).isEqualTo(status);
    }

    private static OperationsStatus.Day operation(Fixture fixture) {
        return new JdbcDashboardSnapshotReader(dataSource).inspect(fixture.account, fixture.date, fixture.date).days().getFirst();
    }

    private static void assertIndependentCurrent(Fixture fixture) throws Exception {
        List<Map<String, Object>> versions = rows("""
                SELECT v.id, v.account_id, v.stat_date, v.source_type, v.content_hash, v.disposition, v.row_count
                FROM scsj_fact_day_state s JOIN scsj_fact_input_version v ON v.id = s.current_input_version_id
                WHERE s.account_id = ? AND s.stat_date = ?
                """, fixture.account, Date.valueOf(fixture.date));
        assertThat(versions).hasSize(1);
        Map<String, Object> version = versions.getFirst();
        assertThat(version.get("content_hash")).isEqualTo(fixture.hash);
        assertThat(version.get("disposition")).isEqualTo("CURRENT");
        assertThat(version.get("source_type")).isEqualTo(fixture.sourceType);
        assertThat(((Number) version.get("row_count")).intValue()).isEqualTo(fixture.expectedRows.size());
        List<Map<String, Object>> facts = rows("""
                SELECT account_id, stat_date, source_row_number, raw_fact_json, row_sha256
                FROM scsj_material_day_fact WHERE input_version_id = ? ORDER BY source_row_number
                """, version.get("id"));
        assertThat(facts).hasSize(fixture.expectedRows.size());
        ArrayNode actualRows = JSON.createArrayNode();
        for (int index = 0; index < facts.size(); index++) {
            Map<String, Object> fact = facts.get(index);
            String raw = (String) fact.get("raw_fact_json");
            assertThat(fact.get("account_id")).isEqualTo(fixture.account);
            assertThat(((Number) fact.get("source_row_number")).intValue()).isEqualTo(index + 1);
            assertThat(fact.get("row_sha256")).isEqualTo(sha(raw.getBytes(StandardCharsets.UTF_8)));
            JsonNode parsed = JSON.readTree(raw);
            assertThat(parsed).isEqualTo(fixture.expectedRows.get(index));
            actualRows.add(parsed);
        }
        assertThat(sha(JSON.writeValueAsBytes(canonical(actualRows)))).isEqualTo(fixture.hash);
    }

    private static int versionCount(Fixture fixture) {
        return rows("SELECT id FROM scsj_fact_input_version WHERE account_id = ? AND stat_date = ?",
                fixture.account, Date.valueOf(fixture.date)).size();
    }

    private static List<List<Map<String, Object>>> otherUnits(String account, LocalDate date) {
        List<List<Map<String, Object>>> result = new ArrayList<>();
        for (String table : List.of("scsj_fact_input_version", "scsj_material_day_fact", "scsj_fact_day_state")) {
            String order = table.equals("scsj_fact_day_state") ? "account_id, stat_date" : "id";
            result.add(rows("SELECT * FROM " + table + " WHERE NOT (account_id = ? AND stat_date = ?) ORDER BY " + order,
                    account, Date.valueOf(date)));
        }
        return result;
    }

    private static List<List<Map<String, Object>>> allFactTables() {
        return otherUnits("nonexistent-synthetic-account", LocalDate.of(1900, 1, 1));
    }

    private static List<Map<String, Object>> rows(String sql, Object... arguments) {
        try { return jdbc.queryForList(sql, arguments); }
        catch (Exception ignored) { throw new AssertionError("SYNTHETIC_INDEPENDENT_SQL_FAILED"); }
    }

    private static Fixture automatic(String account, LocalDate date, int spend, boolean empty) throws Exception {
        Path input = fixtureDirectory();
        ArrayNode facts = JSON.createArrayNode();
        if (!empty) {
            facts.add(metricRow("synthetic-material-private", date, spend));
            facts.add(metricRow("synthetic-zero-private", date, 0));
        }
        String hash = sha(JSON.writeValueAsBytes(canonical(facts)));
        int count = facts.size();
        ObjectNode counts = JSON.createObjectNode().put("rawRowCount", count).put("reportRowCount", count)
                .put("zeroSpendExcludedRows", 0).put("zeroSpendRows", empty ? 0 : 1).put("pageCount", 1);
        ObjectNode material = JSON.createObjectNode().put("schemaVersion", "qianchuan-material-day-standard-1.0.0")
                .put("datasetId", "uni_promotion_video_material_day").put("catalogVersion", "synthetic-catalog")
                .put("statDate", date.toString()).put("projectRef", "sampleproject").put("advertiserId", account)
                .put("dataVersion", "synthetic-" + spend).put("dataHash", hash).put("paginationComplete", true);
        material.set("rows", facts);
        material.set("counts", counts);
        ObjectNode page = JSON.createObjectNode().put("group", "base").put("request_id", "synthetic")
                .put("code", 0).put("page", 1).put("raw_rows", count);
        page.set("page_info", JSON.createObjectNode().put("page", 1).put("page_size", 100)
                .put("total_number", count).put("total_page", 1).put("has_more", false));
        ObjectNode manifest = JSON.createObjectNode().put("schemaVersion", "qianchuan-material-day-manifest-1.0.0")
                .put("runId", "synthetic").put("projectRef", "sampleproject").put("advertiserId", account)
                .put("statDate", date.toString()).put("jobStatus", "COMPLETE").put("pageCount", 1)
                .put("rawRowCount", count).put("reportRowCount", count).put("zeroSpendExcludedRows", 0)
                .put("zeroSpendRows", empty ? 0 : 1).put("paginationComplete", true).put("fieldCatalogVersion", "synthetic-catalog")
                .put("publicationDataVersion", "synthetic-" + spend).put("publicationDataHash", hash);
        manifest.set("pages", JSON.createArrayNode().add(page));
        AutomaticFactFixtures.completeGroups(manifest, material);
        JSON.writeValue(input.resolve("manifest.json"), manifest);
        JSON.writeValue(input.resolve("material_day.json"), material);
        Path config = config(input, account, date, true, input);
        return new Fixture(config, input, account, date, hash, "MCP_AUTO", List.copyOf(facts.valueStream().toList()));
    }

    private static ObjectNode metricRow(String id, LocalDate date, int spend) {
        return AutomaticFactFixtures.row(id, date.toString())
                .put("roi2_material_video_name", "synthetic demo").put("material_create_time_v2", date.minusDays(1).toString())
                .put("stat_cost_for_roi2", spend).put("total_pay_order_gmv_for_roi2", spend * 2)
                .put("live_show_count_for_roi2_v2", 100).put("live_watch_count_for_roi2_v2", 10)
                .put("total_pay_order_count_for_roi2", 2).put("video_like_count_for_roi2", "UNKNOWN");
    }

    private static Fixture manualEmpty(String account, LocalDate date) throws Exception {
        Path input = fixtureDirectory();
        Path workbook = input.resolve("source.xlsx");
        Files.writeString(workbook, "synthetic workbook evidence", StandardCharsets.UTF_8);
        Path facts = input.resolve("facts.jsonl");
        Files.writeString(facts, "", StandardCharsets.UTF_8);
        Path identity = input.resolve("identity.json");
        ObjectNode identityNode = attestation("manual-history-account-attestation-1.0.0", workbook, account)
                .put("validFrom", date.toString()).put("validThrough", date.toString());
        JSON.writeValue(identity, identityNode);
        Path completeness = input.resolve("completeness.json");
        ObjectNode complete = attestation("manual-history-completeness-attestation-1.0.0", workbook, account);
        complete.set("days", JSON.createObjectNode().put(date.toString(), "COMPLETE_NO_ROWS"));
        JSON.writeValue(completeness, complete);
        String hash = sha("[]".getBytes(StandardCharsets.UTF_8));
        ObjectNode day = JSON.createObjectNode().put("schemaVersion", "manual-history-day-evidence-1.0.0")
                .put("sourceType", "MANUAL_HISTORY").put("runId", "synthetic").put("projectRef", "sampleproject")
                .put("accountId", account).put("accountIdentityStatus", "VERIFIED").put("statDate", date.toString())
                .put("status", "READY_FOR_IMPORT").put("databaseImportEligible", true)
                .put("completenessStatus", "COMPLETE_NO_ROWS").put("mappingVersion", "synthetic-mapping")
                .put("factSchemaVersion", "qianchuan-material-day-standard-1.0.0").put("catalogVersion", "synthetic-catalog")
                .put("dataVersion", "synthetic-empty").put("contentHash", hash).put("rowCount", 0)
                .put("zeroSpendRowCount", 0).put("mcpManifestStatus", "NOT_APPLICABLE_NOT_CREATED");
        day.putArray("publicationBlockers");
        day.set("overlap", JSON.createObjectNode().put("status", "NOT_IN_OVERLAP_SCOPE"));
        day.set("sourceWorkbookEvidence", fileEvidence(workbook));
        day.set("factsFileEvidence", fileEvidence(facts));
        day.set("accountIdentityEvidence", fileEvidence(identity));
        day.set("completenessEvidence", fileEvidence(completeness));
        Path dayFile = input.resolve("day-evidence.json");
        JSON.writeValue(dayFile, day);
        return new Fixture(config(input, account, date, false, dayFile), input, account, date, hash, "MANUAL_HISTORY", List.of());
    }

    private static ObjectNode attestation(String type, Path workbook, String account) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", type).put("status", "VERIFIED")
                .put("verificationMethod", "USER_CONFIRMED").put("evidenceRef", "synthetic-test-confirmation")
                .put("projectRef", "sampleproject").put("accountId", account)
                .put("sourceWorkbookSha256", sha(Files.readAllBytes(workbook)));
    }

    private static ObjectNode fileEvidence(Path path) throws Exception {
        return JSON.createObjectNode().put("absolutePath", path.toString()).put("sizeBytes", Files.size(path))
                .put("sha256", sha(Files.readAllBytes(path)));
    }

    private static Path config(Path directory, String account, LocalDate date, boolean auto, Path input) throws Exception {
        ObjectNode config = JSON.createObjectNode().put("workspaceRoot", workspace.toString())
                .put("credentialsPath", credentials.toString()).put("schemaName", schema)
                .put("runId", "synthetic-repair").put("expectedAccountId", account);
        config.putArray("autoDays");
        config.putArray("manualDays");
        config.withArray(auto ? "autoDays" : "manualDays").addObject().put("date", date.toString())
                .put(auto ? "inputDirectory" : "dayEvidencePath", input.toString());
        // The selected-day runner must not attempt to open an unrelated configured day.
        config.withArray("autoDays").addObject().put("date", "2099-01-01")
                .put("inputDirectory", directory.resolve("unselected-missing-input").toString());
        Path path = directory.resolve("config.json");
        JSON.writeValue(path, config);
        return path;
    }

    private static Path fixtureDirectory() throws Exception {
        Path path = fixtureRoot.resolve(UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectory(path);
        require(path.toRealPath().startsWith(fixtureRoot), "SYNTHETIC_FIXTURE_PATH_INVALID");
        return path;
    }

    private static DataSource source(int port, String database, String username, String password) {
        String url = "jdbc:mysql://127.0.0.1:" + port + "/" + database
                + "?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
                + "&connectTimeout=5000&socketTimeout=30000&allowMultiQueries=false&readOnlyPropagatesToServer=true";
        return new DriverManagerDataSource(url, username, password);
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            value.properties().stream().sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = JSON.createArrayNode();
            for (JsonNode child : value) sorted.add(canonical(child));
            return sorted;
        }
        return value.deepCopy();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new AssertionError(code);
    }

    private record Fixture(Path config, Path input, String account, LocalDate date,
                           String hash, String sourceType, List<JsonNode> expectedRows) { }
}
