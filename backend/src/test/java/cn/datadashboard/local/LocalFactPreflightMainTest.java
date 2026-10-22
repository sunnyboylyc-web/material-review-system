package cn.datadashboard.local;

import cn.datadashboard.ingest.persistence.FactImportStore;
import cn.datadashboard.ingest.persistence.JdbcFactImportStore;
import cn.datadashboard.ingest.validation.AutomaticFactFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFactPreflightMainTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT = "1000000000000001";
    private static final String DATE = "2026-09-30";
    @TempDir Path workspace;
    private Path local;
    private Path configPath;
    private ObjectNode config;

    @BeforeEach
    void configurationNeverNeedsRealDataOrCredentials() throws Exception {
        local = Files.createDirectory(workspace.resolve(".local"));
        configPath = local.resolve("config.json");
        config = JSON.createObjectNode();
        config.put("workspaceRoot", workspace.toString());
        config.put("credentialsPath", workspace.resolve("intentionally-missing-never-open-secret.json").toString());
        config.put("schemaName", "SCSJ_LOCAL_SYNTHETIC");
        config.put("runId", "synthetic");
        config.put("expectedAccountId", ACCOUNT);
        config.putArray("autoDays");
        config.putArray("manualDays");
    }

    @Test
    void automaticReadyIsOnlySourceValidationAndPreservesAllBytes() throws Exception {
        Path input = automatic();
        byte[] before = Files.readAllBytes(input.resolve("material_day.json"));
        // Unselected missing inputs and missing credentials must not prevent this one-day check.
        ((tools.jackson.databind.node.ArrayNode) config.get("manualDays")).addObject()
                .put("date", "2026-09-29").put("dayEvidencePath", workspace.resolve("absent.json").toString());
        JsonNode receipt = execute(DATE);
        assertThat(receipt.get("status").stringValue()).isEqualTo("SOURCE_READY");
        assertThat(receipt.get("sourceType").stringValue()).isEqualTo("MCP_AUTO");
        assertThat(receipt.get("rowCount").intValue()).isEqualTo(1);
        assertThat(receipt.get("contentHash").stringValue()).matches("[a-f0-9]{64}");
        assertThat(receipt.get("databaseConnected").booleanValue()).isFalse();
        assertThat(receipt.get("importExecuted").booleanValue()).isFalse();
        assertThat(receipt.get("activationAuthorized").booleanValue()).isFalse();
        assertThat(receipt.get("evidence").size()).isEqualTo(3);
        assertThat(Files.readAllBytes(input.resolve("material_day.json"))).containsExactly(before);
        assertThat(receipt.toString()).doesNotContain(ACCOUNT, "synthetic-material-private", "never-open-secret");
    }

    @Test
    void manualReadyAndConfirmedEmptyHaveDifferentVerifiedRowCounts() throws Exception {
        for (boolean empty : List.of(false, true)) {
            manual(empty);
            JsonNode receipt = execute(DATE);
            assertThat(receipt.get("status").stringValue()).isEqualTo("SOURCE_READY");
            assertThat(receipt.get("sourceType").stringValue()).isEqualTo("MANUAL_HISTORY");
            assertThat(receipt.get("rowCount").intValue()).isEqualTo(empty ? 0 : 1);
            assertThat(receipt.get("evidence").size()).isEqualTo(6);
            assertThat(receipt.toString()).doesNotContain(ACCOUNT, "synthetic-material-private");
        }
    }

    @Test
    void unconfiguredIsNotZeroOrArchiveAbsenceAndDoesNotResolveOtherDays() throws Exception {
        config.withArray("autoDays").addObject().put("date", "2026-09-29")
                .put("inputDirectory", workspace.resolve("absent").toString());
        JsonNode receipt = execute(DATE);
        assertThat(receipt.get("status").stringValue()).isEqualTo("NO_CONFIGURED_INPUT");
        assertUnknown(receipt);
        assertThat(receipt.get("sourceType").isNull()).isTrue();
        assertThat(receipt.get("boundary").stringValue()).contains("does not prove no archive exists");
        assertThat(receipt.get("evidence").size()).isEqualTo(1);
    }

    @Test
    void rejectsIncompletePaginationChangedRowsAndWrongIdentityWithUnknownCount() throws Exception {
        for (String change : List.of("pagination", "hash", "account", "date")) {
            Path input = automatic();
            Path file = input.resolve("material_day.json");
            ObjectNode data = (ObjectNode) JSON.readTree(Files.readAllBytes(file));
            switch (change) {
                case "pagination" -> data.put("paginationComplete", false);
                case "hash" -> ((ObjectNode) data.get("rows").get(0)).put("stat_cost_for_roi2", 99);
                case "account" -> data.put("advertiserId", "999999");
                case "date" -> data.put("statDate", "2026-09-29");
                default -> throw new AssertionError();
            }
            JSON.writeValue(file, data);
            JsonNode receipt = execute(DATE);
            assertThat(receipt.get("status").stringValue()).isEqualTo("INPUT_REJECTED");
            assertUnknown(receipt);
            assertThat(receipt.toString()).doesNotContain(ACCOUNT, "999999", "synthetic-material-private");
        }
    }

    @Test
    void manualRejectsChangedAttestationAndExternalSource() throws Exception {
        Path day = manual(false);
        ObjectNode node = (ObjectNode) JSON.readTree(Files.readAllBytes(day));
        Path identity = Path.of(node.get("accountIdentityEvidence").get("absolutePath").stringValue());
        Files.writeString(identity, "broken secret syntax");
        JsonNode receipt = execute(DATE);
        assertThat(receipt.get("status").stringValue()).isEqualTo("INPUT_REJECTED");
        assertUnknown(receipt);
        assertThat(receipt.toString()).doesNotContain("broken secret syntax");

        day = manual(false);
        node = (ObjectNode) JSON.readTree(Files.readAllBytes(day));
        Path outside = workspace.resolve("outside.xlsx");
        Files.writeString(outside, "external synthetic bytes");
        node.set("sourceWorkbookEvidence", fileEvidence(outside));
        JSON.writeValue(day, node);
        receipt = execute(DATE);
        assertThat(receipt.get("status").stringValue()).isEqualTo("INPUT_REJECTED");
        assertUnknown(receipt);
        assertThat(receipt.toString()).doesNotContain(outside.toString().replace("\\", "\\\\"));
    }

    @Test
    void rejectsDuplicateSelectedDateAndExternalConfigBeforeSourceRead() throws Exception {
        automatic();
        config.withArray("manualDays").addObject().put("date", DATE)
                .put("dayEvidencePath", workspace.resolve("not-read.json").toString());
        JsonNode receipt = execute(DATE);
        assertThat(receipt.get("status").stringValue()).isEqualTo("INPUT_REJECTED");
        assertUnknown(receipt);
        Path outside = workspace.resolve("outside-config.json");
        JSON.writeValue(outside, config);
        var rejected = LocalFactPreflightMain.execute(outside, DATE, workspace);
        assertThat(rejected.status()).isEqualTo(LocalFactPreflightMain.Status.INPUT_REJECTED);
        assertThat(read(rejected).get("evidence").isEmpty()).isTrue();
    }

    @Test
    void invalidDateOrArgumentsHaveOnlyFixedSanitizedConsoleMessage() throws Exception {
        for (String date : List.of("2026-2-03", "2026-02-30", "1899-01-01", "2201-01-01", "secret-value")) {
            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            int exit = LocalFactPreflightMain.run(new String[]{"secret-config", date}, workspace,
                    new PrintStream(stdout), new PrintStream(stderr));
            assertThat(exit).isEqualTo(2);
            assertThat(stdout.toString()).isEmpty();
            assertThat(stderr.toString()).isEqualTo("Local source preflight stopped: arguments, local paths or receipt preflight failed." + System.lineSeparator());
        }
        assertThat(Files.exists(local.resolve("repair-preflight"))).isFalse();
    }

    @Test
    void cliReturnsExpectedExitCodesAndOnlyReceiptLocation() throws Exception {
        automatic();
        JSON.writeValue(configPath, config);
        for (String date : List.of(DATE, "2026-10-12")) {
            var stdout = new ByteArrayOutputStream();
            var stderr = new ByteArrayOutputStream();
            int exit = LocalFactPreflightMain.run(new String[]{configPath.toString(), date}, workspace,
                    new PrintStream(stdout), new PrintStream(stderr));
            assertThat(exit).isEqualTo(date.equals(DATE) ? 0 : 3);
            assertThat(stdout.toString()).startsWith("Local source preflight receipt: ")
                    .doesNotContain(ACCOUNT, "credentials", "material_day");
            assertThat(stderr.toString()).isEmpty();
        }
    }

    @Test
    void neverOverwritesReceiptsAndRejectsAnInvalidReceiptRoot() throws Exception {
        automatic();
        JSON.writeValue(configPath, config);
        var first = LocalFactPreflightMain.execute(configPath, DATE, workspace);
        byte[] bytes = Files.readAllBytes(first.receiptPath());
        var second = LocalFactPreflightMain.execute(configPath, DATE, workspace);
        assertThat(second.receiptPath()).isNotEqualTo(first.receiptPath());
        assertThat(Files.readAllBytes(first.receiptPath())).containsExactly(bytes);
        Path other = Files.createDirectory(workspace.resolve("other"));
        Path otherLocal = Files.createDirectory(other.resolve(".local"));
        Files.writeString(otherLocal.resolve("repair-preflight"), "not a directory");
        assertThatThrownBy(() -> LocalFactPreflightMain.execute(configPath, DATE, other))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("LOCAL_PREFLIGHT_REJECTED");
    }

    @Test
    void inputContextHasNoDataSourceOrDatabaseRepository() {
        var capture = new LocalFactPreflightMain.CaptureStore();
        try (var context = LocalFactPreflightMain.inputContext(capture)) {
            assertThat(context.getBean(FactImportStore.class)).isSameAs(capture);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcFactImportStore.class)).isEmpty();
        }
    }

    private JsonNode execute(String date) throws Exception {
        JSON.writeValue(configPath, config);
        return read(LocalFactPreflightMain.execute(configPath, date, workspace));
    }

    private JsonNode read(LocalFactPreflightMain.Result result) throws Exception {
        return JSON.readTree(Files.readAllBytes(result.receiptPath()));
    }

    private static void assertUnknown(JsonNode receipt) {
        assertThat(receipt.get("rowCount").isNull()).isTrue();
        assertThat(receipt.get("contentHash").isNull()).isTrue();
    }

    private Path automatic() throws Exception {
        Path input = local.resolve("auto");
        Files.createDirectories(input);
        config.putArray("autoDays").addObject().put("date", DATE).put("inputDirectory", input.toString());
        config.putArray("manualDays");
        var rows = AutomaticFactFixtures.row("synthetic-material-private", DATE);
        var array = JSON.createArrayNode().add(rows);
        String hash = LocalFactVerifyMain.sha(JSON.writeValueAsBytes(LocalFactVerifyMain.canonical(array)));
        ObjectNode counts = JSON.createObjectNode().put("rawRowCount", 1).put("reportRowCount", 1)
                .put("zeroSpendExcludedRows", 0).put("zeroSpendRows", 1).put("pageCount", 1);
        ObjectNode material = JSON.createObjectNode().put("schemaVersion", "qianchuan-material-day-standard-1.0.0")
                .put("datasetId", "uni_promotion_video_material_day").put("catalogVersion", "synthetic-catalog")
                .put("statDate", DATE).put("projectRef", "sampleproject").put("advertiserId", ACCOUNT)
                .put("dataVersion", "synthetic-data").put("dataHash", hash).put("paginationComplete", true);
        material.set("rows", array);
        material.set("counts", counts);
        ObjectNode page = JSON.createObjectNode().put("group", "base").put("request_id", "synthetic")
                .put("code", 0).put("page", 1).put("raw_rows", 1);
        page.set("page_info", JSON.createObjectNode().put("page", 1).put("page_size", 100)
                .put("total_number", 1).put("total_page", 1).put("has_more", false));
        ObjectNode manifest = JSON.createObjectNode().put("schemaVersion", "qianchuan-material-day-manifest-1.0.0")
                .put("runId", "synthetic").put("projectRef", "sampleproject").put("advertiserId", ACCOUNT)
                .put("statDate", DATE).put("jobStatus", "COMPLETE").put("pageCount", 1)
                .put("rawRowCount", 1).put("reportRowCount", 1).put("zeroSpendExcludedRows", 0)
                .put("zeroSpendRows", 1).put("paginationComplete", true).put("fieldCatalogVersion", "synthetic-catalog")
                .put("publicationDataVersion", "synthetic-data").put("publicationDataHash", hash);
        manifest.set("pages", JSON.createArrayNode().add(page));
        AutomaticFactFixtures.completeGroups(manifest, material);
        JSON.writeValue(input.resolve("manifest.json"), manifest);
        JSON.writeValue(input.resolve("material_day.json"), material);
        return input;
    }

    private Path manual(boolean empty) throws Exception {
        Path source = local.resolve("source.xlsx");
        Files.writeString(source, "synthetic workbook");
        Path directory = local.resolve("manual");
        Files.createDirectories(directory);
        Path facts = directory.resolve("facts.jsonl");
        String row = "{\"material_id\":\"synthetic-material-private\",\"stat_cost_for_roi2\":0,\"stat_time_day\":\"" + DATE + "\"}";
        Files.writeString(facts, empty ? "" : row + "\n");
        String completeness = empty ? "COMPLETE_NO_ROWS" : "COMPLETE";
        Path identityPath = local.resolve("identity.json");
        ObjectNode identity = attestation("manual-history-account-attestation-1.0.0", source);
        identity.put("validFrom", "2025-11-01").put("validThrough", "2026-10-11");
        JSON.writeValue(identityPath, identity);
        Path completePath = local.resolve("complete.json");
        ObjectNode complete = attestation("manual-history-completeness-attestation-1.0.0", source);
        complete.set("days", JSON.createObjectNode().put(DATE, completeness));
        JSON.writeValue(completePath, complete);
        ObjectNode day = JSON.createObjectNode().put("schemaVersion", "manual-history-day-evidence-1.0.0")
                .put("sourceType", "MANUAL_HISTORY").put("runId", "synthetic").put("projectRef", "sampleproject")
                .put("accountId", ACCOUNT).put("accountIdentityStatus", "VERIFIED").put("statDate", DATE)
                .put("status", "READY_FOR_IMPORT").put("databaseImportEligible", true)
                .put("completenessStatus", completeness).put("mappingVersion", "synthetic-mapping")
                .put("factSchemaVersion", "qianchuan-material-day-standard-1.0.0").put("catalogVersion", "synthetic-catalog")
                .put("dataVersion", "synthetic-data").put("contentHash", LocalFactVerifyMain.sha((empty ? "[]" : "[" + row + "]").getBytes(StandardCharsets.UTF_8)))
                .put("rowCount", empty ? 0 : 1).put("zeroSpendRowCount", empty ? 0 : 1)
                .put("mcpManifestStatus", "NOT_APPLICABLE_NOT_CREATED");
        day.putArray("publicationBlockers");
        day.set("overlap", JSON.createObjectNode().put("status", "NOT_IN_OVERLAP_SCOPE"));
        day.set("sourceWorkbookEvidence", fileEvidence(source));
        day.set("factsFileEvidence", fileEvidence(facts));
        day.set("accountIdentityEvidence", fileEvidence(identityPath));
        day.set("completenessEvidence", fileEvidence(completePath));
        Path evidence = directory.resolve("day-evidence.json");
        JSON.writeValue(evidence, day);
        config.putArray("autoDays");
        config.putArray("manualDays").addObject().put("date", DATE).put("dayEvidencePath", evidence.toString());
        return evidence;
    }

    private ObjectNode attestation(String schema, Path source) throws Exception {
        return JSON.createObjectNode().put("schemaVersion", schema).put("status", "VERIFIED")
                .put("verificationMethod", "USER_CONFIRMED").put("evidenceRef", "synthetic-user-confirmation")
                .put("projectRef", "sampleproject").put("accountId", ACCOUNT)
                .put("sourceWorkbookSha256", LocalFactVerifyMain.sha(Files.readAllBytes(source)));
    }

    private ObjectNode fileEvidence(Path path) throws Exception {
        return JSON.createObjectNode().put("absolutePath", path.toString()).put("sizeBytes", Files.size(path))
                .put("sha256", LocalFactVerifyMain.sha(Files.readAllBytes(path)));
    }
}
