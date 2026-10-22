package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.FactInputValidationReceipt;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;
import cn.datadashboard.ingest.validation.FactInputValidator;
import cn.datadashboard.ingest.validation.AutomaticFactFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreparedFactBatchReaderTest {

    private static final String ACCOUNT_ID = "1000000000000001";
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 11);

    @TempDir
    Path temporaryDirectory;

    private JsonMapper mapper;
    private FactInputValidator validator;
    private PreparedFactBatchReader reader;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
        validator = new FactInputValidator();
        reader = new PreparedFactBatchReader();
    }

    @Test
    void preparesExactSnapshotAndPreservesNullUnknownAndZero() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("preserve-values"), false);
        FactInputValidationRequest request = request(input);
        FactInputValidationReceipt receipt = validator.validate(request);

        PreparedFactBatch batch = reader.prepare(new VersionedFactImportCommand(request, receipt));

        assertThat(receipt.accepted()).isTrue();
        assertThat(batch.sourceType()).isEqualTo(FactSourceType.MCP_AUTO);
        assertThat(batch.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(batch.statDate()).isEqualTo(STAT_DATE);
        assertThat(batch.rows()).hasSize(2);
        JsonNode first = mapper.readTree(batch.rows().getFirst().rawFactJson());
        assertThat(first.get("stat_cost_for_roi2").isNumber()).isTrue();
        assertThat(first.get("stat_cost_for_roi2").intValue()).isZero();
        assertThat(first.get("video_like_count_for_roi2").stringValue()).isEqualTo("UNKNOWN");
        assertThat(first.get("video_follow_count_for_roi2").isNull()).isTrue();
        assertThat(batch.rows().getFirst().rowSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsSourceBytesChangedAfterValidation() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("changed-after-validation"), false);
        FactInputValidationRequest request = request(input);
        FactInputValidationReceipt receipt = validator.validate(request);
        Files.writeString(
                input.resolve("material_day.json"),
                System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        assertThatThrownBy(() -> reader.prepare(new VersionedFactImportCommand(request, receipt)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);
    }

    @Test
    void rejectsDuplicateMaterialIdBeforeDatabaseTransaction() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("duplicate-material"), true);
        FactInputValidationRequest request = request(input);
        FactInputValidationReceipt receipt = validator.validate(request);

        assertThat(receipt.accepted()).isTrue();
        assertThatThrownBy(() -> reader.prepare(new VersionedFactImportCommand(request, receipt)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.FACT_ROW_INVALID);
    }

    @Test
    void reformattedFilesWithSameOrderedRowSemanticsRemainEligibleForIdempotency() throws Exception {
        Path originalInput = writeValidPair(temporaryDirectory.resolve("original-format"), false);
        Path reformattedInput = writeReformattedEquivalentPair(
                originalInput,
                temporaryDirectory.resolve("reformatted-equivalent"));
        FactInputValidationReceipt originalReceipt = validator.validate(request(originalInput));
        FactInputValidationReceipt reformattedReceipt = validator.validate(request(reformattedInput));

        assertThat(originalReceipt.accepted()).isTrue();
        assertThat(reformattedReceipt.accepted()).isTrue();
        assertThat(reformattedReceipt.observedDataHash()).isEqualTo(originalReceipt.observedDataHash());
        assertThat(reformattedReceipt.manifestFile().sha256())
                .isNotEqualTo(originalReceipt.manifestFile().sha256());
        assertThat(reformattedReceipt.materialDayFile().sha256())
                .isNotEqualTo(originalReceipt.materialDayFile().sha256());

        PreparedFactBatch original = reader.prepare(new VersionedFactImportCommand(
                request(originalInput), originalReceipt));
        PreparedFactBatch reformatted = reader.prepare(new VersionedFactImportCommand(
                request(reformattedInput), reformattedReceipt));
        assertThat(reformatted.rows()).extracting(PreparedMaterialFact::materialId)
                .containsExactlyElementsOf(original.rows().stream()
                        .map(PreparedMaterialFact::materialId)
                        .toList());

        StoredFactVersionSnapshot storedVersion = new StoredFactVersionSnapshot(
                original.accountId(),
                original.statDate(),
                original.contentHash(),
                FactVersionDisposition.CURRENT,
                original.rows().size(),
                original.manifestEvidence().absolutePath(),
                original.manifestEvidence().sizeBytes(),
                original.manifestEvidence().sha256(),
                original.materialEvidence().absolutePath(),
                original.materialEvidence().sizeBytes(),
                original.materialEvidence().sha256());
        List<StoredMaterialFactSnapshot> storedFacts = original.rows().stream()
                .map(row -> new StoredMaterialFactSnapshot(
                        original.accountId(),
                        original.statDate(),
                        row.materialId(),
                        row.sourceRowNumber(),
                        row.rawFactJson(),
                        row.rowSha256()))
                .toList();

        new StoredFactVersionVerifier().verify(reformatted, storedVersion, storedFacts);
    }

    private FactInputValidationRequest request(Path input) {
        return new FactInputValidationRequest("run-m2-synthetic", input, ACCOUNT_ID, STAT_DATE);
    }

    private Path writeValidPair(Path input, boolean duplicateMaterialId) throws Exception {
        Files.createDirectories(input);
        ArrayNode rows = mapper.createArrayNode();
        rows.add(row("synthetic-material-1"));
        rows.add(row(duplicateMaterialId ? "synthetic-material-1" : "synthetic-material-2"));
        String dataHash = sha256(mapper.writeValueAsBytes(sortRecursively(rows)));
        String dataVersion = "synthetic-data-version-" + dataHash.substring(0, 12);

        ObjectNode counts = mapper.createObjectNode();
        counts.put("rawRowCount", 2);
        counts.put("reportRowCount", 2);
        counts.put("zeroSpendExcludedRows", 0);
        counts.put("zeroSpendRows", 1);
        counts.put("pageCount", 1);

        ObjectNode material = mapper.createObjectNode();
        material.put("schemaVersion", "qianchuan-material-day-standard-1.0.0");
        material.put("datasetId", "uni_promotion_video_material_day");
        material.put("catalogVersion", "synthetic-catalog-1");
        material.put("statDate", STAT_DATE.toString());
        material.put("projectRef", "synthetic-project");
        material.put("advertiserId", ACCOUNT_ID);
        material.put("dataVersion", dataVersion);
        material.put("dataHash", dataHash);
        material.set("rows", rows);
        material.set("counts", counts);
        material.put("paginationComplete", true);

        ObjectNode pageInfo = mapper.createObjectNode();
        pageInfo.put("page", 1);
        pageInfo.put("page_size", 100);
        pageInfo.put("total_number", 2);
        pageInfo.put("total_page", 1);
        pageInfo.put("has_more", false);
        ObjectNode page = mapper.createObjectNode();
        page.put("group", "base");
        page.put("request_id", "synthetic-request");
        page.put("code", 0);
        page.put("page", 1);
        page.set("page_info", pageInfo);
        page.put("raw_rows", 2);
        ArrayNode pages = mapper.createArrayNode().add(page);

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", "qianchuan-material-day-manifest-1.0.0");
        manifest.put("runId", "2026-10-11-synthetic-project");
        manifest.put("projectRef", "synthetic-project");
        manifest.put("advertiserId", ACCOUNT_ID);
        manifest.put("statDate", STAT_DATE.toString());
        manifest.put("jobStatus", "COMPLETE");
        manifest.put("pageCount", 1);
        manifest.put("rawRowCount", 2);
        manifest.put("reportRowCount", 2);
        manifest.put("zeroSpendExcludedRows", 0);
        manifest.put("zeroSpendRows", 1);
        manifest.put("paginationComplete", true);
        manifest.put("fieldCatalogVersion", "synthetic-catalog-1");
        manifest.put("publicationDataVersion", dataVersion);
        manifest.put("publicationDataHash", dataHash);
        manifest.set("pages", pages);
        AutomaticFactFixtures.completeGroups(manifest, material);

        mapper.writerWithDefaultPrettyPrinter().writeValue(input.resolve("manifest.json"), manifest);
        mapper.writerWithDefaultPrettyPrinter().writeValue(input.resolve("material_day.json"), material);
        return input;
    }

    private Path writeReformattedEquivalentPair(Path originalInput, Path output) throws Exception {
        Files.createDirectories(output);
        JsonNode originalMaterial = mapper.readTree(Files.readAllBytes(originalInput.resolve("material_day.json")));
        JsonNode originalManifest = mapper.readTree(Files.readAllBytes(originalInput.resolve("manifest.json")));

        ArrayNode reorderedRowKeys = mapper.createArrayNode();
        ArrayNode sourceRows = (ArrayNode) originalMaterial.get("rows");
        for (int index = 0; index < sourceRows.size(); index++) {
            JsonNode sourceRow = sourceRows.get(index);
            ObjectNode row = mapper.createObjectNode();
            sourceRow.properties().stream().sorted(java.util.Map.Entry.<String, JsonNode>comparingByKey().reversed())
                    .forEach(entry -> row.set(entry.getKey(), entry.getValue()));
            reorderedRowKeys.add(row);
        }

        ObjectNode material = mapper.createObjectNode();
        material.put("paginationComplete", true);
        material.set("counts", originalMaterial.get("counts"));
        material.set("rows", reorderedRowKeys);
        material.put("dataHash", originalMaterial.get("dataHash").stringValue());
        material.put("dataVersion", originalMaterial.get("dataVersion").stringValue());
        material.put("advertiserId", originalMaterial.get("advertiserId").stringValue());
        material.put("projectRef", originalMaterial.get("projectRef").stringValue());
        material.put("statDate", originalMaterial.get("statDate").stringValue());
        material.put("catalogVersion", originalMaterial.get("catalogVersion").stringValue());
        material.put("datasetId", originalMaterial.get("datasetId").stringValue());
        material.put("schemaVersion", originalMaterial.get("schemaVersion").stringValue());
        material.put("syntheticNonRowsMetadata", "reformatted-copy");

        ObjectNode manifest = mapper.createObjectNode();
        manifest.set("pages", originalManifest.get("pages"));
        manifest.put("publicationDataHash", originalManifest.get("publicationDataHash").stringValue());
        manifest.put("publicationDataVersion", originalManifest.get("publicationDataVersion").stringValue());
        manifest.put("fieldCatalogVersion", originalManifest.get("fieldCatalogVersion").stringValue());
        manifest.put("paginationComplete", true);
        manifest.put("zeroSpendRows", originalManifest.get("zeroSpendRows").intValue());
        manifest.put("zeroSpendExcludedRows", originalManifest.get("zeroSpendExcludedRows").intValue());
        manifest.put("reportRowCount", originalManifest.get("reportRowCount").intValue());
        manifest.put("rawRowCount", originalManifest.get("rawRowCount").intValue());
        manifest.put("pageCount", originalManifest.get("pageCount").intValue());
        manifest.put("jobStatus", originalManifest.get("jobStatus").stringValue());
        manifest.put("statDate", originalManifest.get("statDate").stringValue());
        manifest.put("advertiserId", originalManifest.get("advertiserId").stringValue());
        manifest.put("projectRef", originalManifest.get("projectRef").stringValue());
        manifest.put("runId", originalManifest.get("runId").stringValue());
        manifest.put("schemaVersion", originalManifest.get("schemaVersion").stringValue());
        manifest.put("syntheticRequestedAt", "2026-10-12T00:00:00Z");

        mapper.writeValue(output.resolve("material_day.json"), material);
        mapper.writeValue(output.resolve("manifest.json"), manifest);
        return output;
    }

    private ObjectNode row(String materialId) {
        ObjectNode row = AutomaticFactFixtures.row(materialId, STAT_DATE.toString());
        row.put("stat_cost_for_roi2", 0);
        row.put("video_like_count_for_roi2", "UNKNOWN");
        row.putNull("video_follow_count_for_roi2");
        return row;
    }

    private JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey()))
                    .forEach(entry -> sorted.set(entry.getKey(), sortRecursively(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            ((ArrayNode) node).elements().forEach(element -> sorted.add(sortRecursively(element)));
            return sorted;
        }
        return node.deepCopy();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
