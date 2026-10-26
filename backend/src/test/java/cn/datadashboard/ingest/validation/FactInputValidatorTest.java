package cn.datadashboard.ingest.validation;

import cn.datadashboard.configuration.MetricSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactInputValidatorTest {

    private static final String ACCOUNT_ID = "1000000000000001";
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 11);

    @TempDir
    Path temporaryDirectory;

    private JsonMapper mapper;
    private FactInputValidator validator;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
        validator = new FactInputValidator();
    }

    @Test
    void acceptsCompleteSyntheticPairAndReturnsFileEvidence() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("accepted"));

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.issues()).isEmpty();
        assertThat(receipt.observedProjectRef()).isEqualTo("synthetic-project");
        assertThat(receipt.pageCount()).isEqualTo(6);
        assertThat(receipt.reportRowCount()).isEqualTo(2);
        assertThat(receipt.manifestFile().sha256()).matches("[0-9a-f]{64}");
        assertThat(receipt.materialDayFile().sha256()).matches("[0-9a-f]{64}");
        assertThat(receipt.manifestFile().sizeBytes()).isPositive();
        assertThat(receipt.materialDayFile().sizeBytes()).isPositive();
    }

    @Test
    void rejectsExpectedAccountMismatch() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("account-mismatch"));
        FactInputValidationRequest request = new FactInputValidationRequest(
                "run-account-mismatch", input, "9999999999999999", STAT_DATE);

        FactInputValidationReceipt receipt = validator.validate(request);

        assertRejectedWith(receipt, ValidationIssueCode.ACCOUNT_ID_MISMATCH);
    }

    @Test
    void rejectsExpectedDateMismatch() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("date-mismatch"));
        FactInputValidationRequest request = new FactInputValidationRequest(
                "run-date-mismatch", input, ACCOUNT_ID, STAT_DATE.plusDays(1));

        FactInputValidationReceipt receipt = validator.validate(request);

        assertRejectedWith(receipt, ValidationIssueCode.STAT_DATE_MISMATCH);
        assertThat(receipt.issues()).extracting(ValidationIssue::code)
                .contains(ValidationIssueCode.ROW_DATE_MISMATCH);
    }

    @Test
    void rejectsRowsChangedWithoutUpdatingDataHash() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("hash-mismatch"));
        Path materialPath = input.resolve(FactInputValidator.MATERIAL_DAY_FILE_NAME);
        ObjectNode material = (ObjectNode) mapper.readTree(Files.readAllBytes(materialPath));
        ((ObjectNode) material.get("rows").get(0)).put("stat_cost_for_roi2", 999);
        mapper.writerWithDefaultPrettyPrinter().writeValue(materialPath, material);

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.ROWS_HASH_MISMATCH);
        assertThat(receipt.materialDayFile().sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsManifestAndMaterialHashDisagreement() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("declared-hash-mismatch"));
        Path manifestPath = input.resolve(FactInputValidator.MANIFEST_FILE_NAME);
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        manifest.put("publicationDataHash", "0".repeat(64));
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath, manifest);

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.PUBLICATION_HASH_MISMATCH);
    }

    @Test
    void rejectsMissingPageEvenWhenCompletionFlagsAreTrue() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("missing-page"));
        Path manifestPath = input.resolve(FactInputValidator.MANIFEST_FILE_NAME);
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        ArrayNode pages = (ArrayNode) manifest.get("pages");
        pages.remove(1);
        manifest.put("pageCount", 2);
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath, manifest);

        Path materialPath = input.resolve(FactInputValidator.MATERIAL_DAY_FILE_NAME);
        ObjectNode material = (ObjectNode) mapper.readTree(Files.readAllBytes(materialPath));
        ((ObjectNode) material.get("counts")).put("pageCount", 2);
        mapper.writerWithDefaultPrettyPrinter().writeValue(materialPath, material);

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.PAGINATION_INCOMPLETE);
        assertThat(receipt.issues()).extracting(ValidationIssue::code)
                .contains(ValidationIssueCode.RAW_ROW_COUNT_MISMATCH);
    }

    @Test
    void rejectsExplicitIncompletePagination() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("flag-incomplete"));
        Path manifestPath = input.resolve(FactInputValidator.MANIFEST_FILE_NAME);
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(manifestPath));
        manifest.put("paginationComplete", false);
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath, manifest);

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.PAGINATION_INCOMPLETE);
    }

    @Test
    void rejectsMalformedJsonWithoutLeakingRawContentInReceipt() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("malformed-json"));
        Files.writeString(input.resolve(FactInputValidator.MANIFEST_FILE_NAME), "{\"raw_marker\":");

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.JSON_INVALID);
        assertThat(receipt.manifestFile().sha256()).matches("[0-9a-f]{64}");
        assertThat(receipt.issues()).extracting(ValidationIssue::message)
                .allMatch(message -> !message.contains("raw_marker"));
    }

    @Test
    void rejectsMissingInputFileWithExplicitReceipt() throws Exception {
        Path input = writeValidPair(temporaryDirectory.resolve("missing-file"));
        Files.delete(input.resolve(FactInputValidator.MATERIAL_DAY_FILE_NAME));

        FactInputValidationReceipt receipt = validator.validate(request(input));

        assertRejectedWith(receipt, ValidationIssueCode.INPUT_FILE_MISSING);
        assertThat(receipt.manifestFile()).isNotNull();
        assertThat(receipt.materialDayFile()).isNull();
    }

    private FactInputValidationRequest request(Path input) {
        return new FactInputValidationRequest("run-synthetic", input, ACCOUNT_ID, STAT_DATE);
    }

    @Test
    void acceptsSelectedV2AndLegacyWithoutConsultingActiveSettings() throws Exception {
        Path selected = writeV2Pair(temporaryDirectory.resolve("selected"));
        assertThat(validator.validate(request(selected)).accepted()).isTrue();
        Path legacy = writeValidPair(temporaryDirectory.resolve("legacy"));
        assertThat(validator.validate(request(legacy)).accepted()).isTrue();
        assertThat(validator.validate(request(selected)).accepted()).isTrue();
    }

    @Test
    void rejectsMissingExtraFieldsAndUnknownLegacyCatalogEvenWithValidRowHash() throws Exception {
        for (String change : List.of("missing", "extra", "catalog")) {
            Path input = writeValidPair(temporaryDirectory.resolve(change));
            ObjectNode material = read(input, "material_day.json");
            ObjectNode manifest = read(input, "manifest.json");
            ObjectNode row = (ObjectNode) material.get("rows").get(0);
            if (change.equals("missing")) row.remove("video_like_count_for_roi2");
            if (change.equals("extra")) row.put("unexpected_metric", 0);
            if (change.equals("catalog")) {
                material.put("catalogVersion", "unknown");
                manifest.put("fieldCatalogVersion", "unknown");
            }
            save(input, manifest, material);
            assertRejectedWith(validator.validate(request(input)), change.equals("catalog")
                    ? ValidationIssueCode.ACQUISITION_CONTRACT_INVALID : ValidationIssueCode.ROW_FIELDS_MISMATCH);
        }
    }

    @Test
    void rejectsInvalidOrUnboundV2SelectionMetadata() throws Exception {
        for (String change : List.of("mismatch", "catalogHash", "selectionHash", "required", "duplicate", "unknown", "order", "revision", "extra", "legacyDowngrade")) {
            Path input = writeV2Pair(temporaryDirectory.resolve("v2-" + change));
            ObjectNode material = read(input, "material_day.json");
            ObjectNode manifest = read(input, "manifest.json");
            ObjectNode contract = (ObjectNode) material.get("acquisitionContract");
            ArrayNode metrics = (ArrayNode) contract.get("enabledMetrics");
            switch (change) {
                case "mismatch" -> contract.put("configurationRevision", "b".repeat(64));
                case "catalogHash" -> contract.put("catalogSha256", "0".repeat(64));
                case "selectionHash" -> contract.put("selectionSha256", "0".repeat(64));
                case "required" -> metrics.remove(0);
                case "duplicate" -> metrics.add(metrics.get(0));
                case "unknown" -> metrics.add("unknown");
                case "order" -> { var first = metrics.remove(0); metrics.add(first); }
                case "revision" -> contract.put("configurationRevision", "bad");
                case "extra" -> contract.put("unexpected", true);
                case "legacyDowngrade" -> {
                    material.put("schemaVersion", FactInputValidator.MATERIAL_SCHEMA_VERSION);
                    manifest.put("schemaVersion", FactInputValidator.MANIFEST_SCHEMA_VERSION);
                }
                default -> throw new AssertionError();
            }
            if (!change.equals("mismatch")) manifest.set("acquisitionContract", contract.deepCopy());
            save(input, manifest, material);
            assertRejectedWith(validator.validate(request(input)), ValidationIssueCode.ACQUISITION_CONTRACT_INVALID);
        }
    }

    @Test
    void completeEmptyDayStillRequiresEveryExpectedGroupAndCorrectPageSize() throws Exception {
        Path input = writeV2Pair(temporaryDirectory.resolve("empty-v2"));
        ObjectNode material = read(input, "material_day.json"), manifest = read(input, "manifest.json");
        material.putArray("rows");
        ObjectNode counts = (ObjectNode) material.get("counts");
        counts.put("reportRowCount", 0).put("zeroSpendRows", 0);
        manifest.put("reportRowCount", 0).put("zeroSpendRows", 0);
        manifest.putArray("pages");
        AutomaticFactFixtures.completeGroups(manifest, material);
        save(input, manifest, material);
        assertThat(validator.validate(request(input)).accepted()).isTrue();
        ArrayNode pages = (ArrayNode) manifest.get("pages");
        for (JsonNode page : pages) ((ObjectNode)page.get("page_info")).put("total_page",0);
        save(input,manifest,material);
        assertThat(validator.validate(request(input)).accepted()).isTrue();
        pages.remove(4);
        manifest.put("pageCount", 4); counts.put("pageCount", 4);
        save(input, manifest, material);
        assertRejectedWith(validator.validate(request(input)), ValidationIssueCode.ACQUISITION_CONTRACT_INVALID);
        AutomaticFactFixtures.completeGroups(manifest, material);
        ((ObjectNode) pages.get(0).get("page_info")).put("page_size", 200);
        save(input, manifest, material);
        assertRejectedWith(validator.validate(request(input)), ValidationIssueCode.ACQUISITION_CONTRACT_INVALID);
    }

    private Path writeV2Pair(Path input) throws Exception {
        writeValidPair(input);
        ObjectNode material = read(input, "material_day.json"), manifest = read(input, "manifest.json");
        List<String> selected = MetricSelection.validate(MetricSelection.requiredMetrics());
        for (JsonNode row : material.get("rows")) {
            for (String metric : MetricSelection.allMetrics()) if (!selected.contains(metric)) ((ObjectNode) row).remove(metric);
        }
        ObjectNode contract = mapper.createObjectNode().put("catalogId", MetricSelection.CATALOG_ID)
                .put("catalogSha256", MetricSelection.catalogSha256()).put("selectionSha256", MetricSelection.selectionSha256(selected))
                .put("configurationRevision", "a".repeat(64));
        var array = contract.putArray("enabledMetrics"); selected.forEach(array::add);
        material.put("schemaVersion", "qianchuan-material-day-standard-2.0.0").set("acquisitionContract", contract);
        manifest.put("schemaVersion", "qianchuan-material-day-manifest-2.0.0").set("acquisitionContract", contract.deepCopy());
        save(input, manifest, material);
        return input;
    }

    private ObjectNode read(Path input, String name) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readAllBytes(input.resolve(name)));
    }

    private void save(Path input, ObjectNode manifest, ObjectNode material) throws Exception {
        String hash = new CanonicalRowsHasher(mapper).sha256(material.get("rows"));
        material.put("dataHash", hash); manifest.put("publicationDataHash", hash);
        mapper.writeValue(input.resolve("manifest.json"), manifest);
        mapper.writeValue(input.resolve("material_day.json"), material);
    }

    private Path writeValidPair(Path input) throws Exception {
        Files.createDirectories(input);
        ArrayNode rows = mapper.createArrayNode();
        rows.add(row("material-synthetic-1", "合成素材一", 12.5));
        rows.add(row("material-synthetic-2", "合成素材二", 0));
        String dataHash = new CanonicalRowsHasher(mapper).sha256(rows);
        String dataVersion = "uni_promotion_video_material_day-2026-10-11-synthetic-" + dataHash.substring(0, 12);

        ObjectNode counts = mapper.createObjectNode();
        counts.put("rawRowCount", 4);
        counts.put("reportRowCount", 2);
        counts.put("zeroSpendExcludedRows", 0);
        counts.put("zeroSpendRows", 1);
        counts.put("pageCount", 3);

        ObjectNode material = mapper.createObjectNode();
        material.put("schemaVersion", FactInputValidator.MATERIAL_SCHEMA_VERSION);
        material.put("datasetId", FactInputValidator.DATASET_ID);
        material.put("catalogVersion", "synthetic-catalog-1");
        material.put("statDate", STAT_DATE.toString());
        material.put("projectRef", "synthetic-project");
        material.put("advertiserId", ACCOUNT_ID);
        material.put("dataVersion", dataVersion);
        material.put("dataHash", dataHash);
        material.set("rows", rows);
        material.set("counts", counts);
        material.put("paginationComplete", true);

        ArrayNode pages = mapper.createArrayNode();
        pages.add(page("base", 1, 2, true, 1, 2, 100));
        pages.add(page("base", 2, 2, false, 1, 2, 100));
        pages.add(page("video", 1, 1, false, 2, 2, 200));

        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", FactInputValidator.MANIFEST_SCHEMA_VERSION);
        manifest.put("runId", "2026-10-11-synthetic-project");
        manifest.put("projectRef", "synthetic-project");
        manifest.put("advertiserId", ACCOUNT_ID);
        manifest.put("statDate", STAT_DATE.toString());
        manifest.put("jobStatus", "COMPLETE");
        manifest.put("pageCount", 3);
        manifest.put("rawRowCount", 4);
        manifest.put("reportRowCount", 2);
        manifest.put("zeroSpendExcludedRows", 0);
        manifest.put("zeroSpendRows", 1);
        manifest.put("paginationComplete", true);
        manifest.put("fieldCatalogVersion", "synthetic-catalog-1");
        manifest.put("publicationDataVersion", dataVersion);
        manifest.put("publicationDataHash", dataHash);
        manifest.set("pages", pages);
        AutomaticFactFixtures.completeGroups(manifest, material);

        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(input.resolve(FactInputValidator.MANIFEST_FILE_NAME), manifest);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(input.resolve(FactInputValidator.MATERIAL_DAY_FILE_NAME), material);
        return input;
    }

    private ObjectNode row(String materialId, String name, Number metric) {
        ObjectNode row = AutomaticFactFixtures.row(materialId, STAT_DATE.toString());
        row.put("roi2_material_video_name", name);
        if (metric instanceof Integer integer) {
            row.put("stat_cost_for_roi2", integer);
        } else {
            row.put("stat_cost_for_roi2", metric.doubleValue());
        }
        return row;
    }

    private ObjectNode page(
            String group,
            int page,
            int totalPages,
            boolean hasMore,
            int rawRows,
            int totalNumber,
            int pageSize
    ) {
        ObjectNode info = mapper.createObjectNode();
        info.put("page", page);
        info.put("page_size", pageSize);
        info.put("total_number", totalNumber);
        info.put("total_page", totalPages);
        info.put("has_more", hasMore);

        ObjectNode summary = mapper.createObjectNode();
        summary.put("group", group);
        summary.put("request_id", "synthetic-" + group + "-" + page);
        summary.put("code", 0);
        summary.put("page", page);
        summary.set("page_info", info);
        summary.put("raw_rows", rawRows);
        return summary;
    }

    private static void assertRejectedWith(
            FactInputValidationReceipt receipt,
            ValidationIssueCode issueCode
    ) {
        assertThat(receipt.status()).isEqualTo(ValidationStatus.REJECTED);
        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.issues()).extracting(ValidationIssue::code).contains(issueCode);
    }
}
