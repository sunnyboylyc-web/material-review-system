package cn.datadashboard.ingest.validation;

import cn.datadashboard.configuration.MetricSelection;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

@Service
public class FactInputValidator {

    static final String MANIFEST_FILE_NAME = "manifest.json";
    static final String MATERIAL_DAY_FILE_NAME = "material_day.json";
    static final String MANIFEST_SCHEMA_VERSION = "qianchuan-material-day-manifest-1.0.0";
    static final String MATERIAL_SCHEMA_VERSION = "qianchuan-material-day-standard-1.0.0";
    static final String DATASET_ID = "uni_promotion_video_material_day";

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final JsonMapper mapper;
    private final CanonicalRowsHasher rowsHasher;

    public FactInputValidator() {
        this(JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build());
    }

    FactInputValidator(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.rowsHasher = new CanonicalRowsHasher(mapper);
    }

    public FactInputValidationReceipt validate(FactInputValidationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<ValidationIssue> issues = new ArrayList<>();
        Path directory = request.inputDirectory().toAbsolutePath().normalize();

        if (!Files.isDirectory(directory)) {
            add(issues, ValidationIssueCode.INPUT_DIRECTORY_INVALID, "inputDirectory",
                    "Input directory does not exist or is not a directory");
            return receipt(request, null, null, null, null, null, null, issues);
        }

        LoadedJson manifest = load(directory.resolve(MANIFEST_FILE_NAME), "manifest", issues);
        LoadedJson material = load(directory.resolve(MATERIAL_DAY_FILE_NAME), "materialDay", issues);
        if (manifest == null || material == null || manifest.root() == null || material.root() == null) {
            return receipt(request, manifest, material, null, null, null, null, issues);
        }

        JsonNode manifestRoot = manifest.root();
        JsonNode materialRoot = material.root();
        validateFixedContract(manifestRoot, materialRoot, issues);
        validateIdentityAndDate(request, manifestRoot, materialRoot, issues);
        validateMirroredMetadata(manifestRoot, materialRoot, issues);
        validateRows(request, manifestRoot, materialRoot, issues);
        validatePagination(manifestRoot, materialRoot, issues);
        validateAcquisitionContract(manifestRoot, materialRoot, issues);

        String projectRef = textValue(manifestRoot, "projectRef");
        String dataHash = textValue(materialRoot, "dataHash");
        Integer pageCount = integerValue(manifestRoot, "pageCount");
        Integer reportRowCount = integerValue(manifestRoot, "reportRowCount");
        return receipt(request, manifest, material, projectRef, dataHash, pageCount, reportRowCount, issues);
    }

    private LoadedJson load(Path path, String fieldPrefix, List<ValidationIssue> issues) {
        if (!Files.exists(path)) {
            add(issues, ValidationIssueCode.INPUT_FILE_MISSING, fieldPrefix,
                    fieldPrefix + " input file is missing");
            return null;
        }
        if (!Files.isRegularFile(path)) {
            add(issues, ValidationIssueCode.INPUT_FILE_NOT_REGULAR, fieldPrefix,
                    fieldPrefix + " input is not a regular file");
            return null;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException exception) {
            add(issues, ValidationIssueCode.INPUT_FILE_READ_FAILED, fieldPrefix,
                    fieldPrefix + " input file could not be read");
            return null;
        }

        InputFileEvidence evidence = new InputFileEvidence(
                path.getFileName().toString(),
                path.toAbsolutePath().normalize().toString(),
                bytes.length,
                CanonicalRowsHasher.sha256Hex(bytes));
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                add(issues, ValidationIssueCode.JSON_INVALID, fieldPrefix,
                        fieldPrefix + " JSON root must be an object");
                return new LoadedJson(null, evidence);
            }
            return new LoadedJson(root, evidence);
        } catch (JacksonException exception) {
            add(issues, ValidationIssueCode.JSON_INVALID, fieldPrefix,
                    fieldPrefix + " input is not valid unambiguous JSON");
            return new LoadedJson(null, evidence);
        }
    }

    private void validateFixedContract(JsonNode manifest, JsonNode material, List<ValidationIssue> issues) {
        boolean versionTwo = "qianchuan-material-day-standard-2.0.0".equals(textValue(material, "schemaVersion"));
        compareFixedText(manifest, "schemaVersion", versionTwo ? "qianchuan-material-day-manifest-2.0.0" : MANIFEST_SCHEMA_VERSION,
                ValidationIssueCode.SCHEMA_VERSION_UNSUPPORTED, "manifest.schemaVersion", issues);
        compareFixedText(material, "schemaVersion", versionTwo ? "qianchuan-material-day-standard-2.0.0" : MATERIAL_SCHEMA_VERSION,
                ValidationIssueCode.SCHEMA_VERSION_UNSUPPORTED, "materialDay.schemaVersion", issues);
        compareFixedText(material, "datasetId", DATASET_ID,
                ValidationIssueCode.DATASET_ID_UNSUPPORTED, "materialDay.datasetId", issues);
        compareFixedText(manifest, "jobStatus", "COMPLETE",
                ValidationIssueCode.JOB_NOT_COMPLETE, "manifest.jobStatus", issues);
    }

    /** Verify the immutable acquisition snapshot, never the currently active maintenance settings. */
    private void validateAcquisitionContract(JsonNode manifest, JsonNode material, List<ValidationIssue> issues) {
        try {
            if (!MetricSelection.HISTORICAL_CATALOG.equals(textValue(material, "catalogVersion"))
                    || !MetricSelection.HISTORICAL_CATALOG.equals(textValue(manifest, "fieldCatalogVersion"))) {
                throw new IllegalArgumentException();
            }
            List<String> selected = MetricSelection.allMetrics();
            boolean versionTwo = "qianchuan-material-day-standard-2.0.0".equals(textValue(material, "schemaVersion"));
            if (versionTwo) {
                JsonNode contract = material.get("acquisitionContract");
                if (contract == null || !contract.equals(manifest.get("acquisitionContract"))
                        || !keys(contract).equals(Set.of("catalogId", "catalogSha256", "enabledMetrics", "selectionSha256", "configurationRevision"))
                        || !MetricSelection.CATALOG_ID.equals(textValue(contract, "catalogId"))
                        || !MetricSelection.catalogSha256().equals(textValue(contract, "catalogSha256"))) throw new IllegalArgumentException();
                List<String> declared = MetricSelection.strings(contract.get("enabledMetrics"));
                selected = MetricSelection.validate(declared);
                String revision = textValue(contract, "configurationRevision");
                if (!selected.equals(declared) || !MetricSelection.selectionSha256(selected).equals(textValue(contract, "selectionSha256"))
                        || revision == null || !SHA_256_PATTERN.matcher(revision).matches()) throw new IllegalArgumentException();
            } else if (material.has("acquisitionContract") || manifest.has("acquisitionContract")) {
                throw new IllegalArgumentException();
            }
            if (material.has("collectionSelection") || manifest.has("collectionSelection")) {
                JsonNode selection = material.get("collectionSelection");
                if (selection == null || !selection.equals(manifest.get("collectionSelection"))
                        || !keys(selection).equals(Set.of("theme", "dataTopic", "configurationRevision", "awemeId"))) throw new IllegalArgumentException();
                String theme = textValue(selection, "theme");
                String topic = "QUANYU".equals(theme) ? "SITE_PROMOTION_POST_DATA_VIDEO"
                        : "CHENGFANG".equals(theme) ? "OVERALL_ROI_LIVE_MATERIAL_VIDEO" : null;
                String anchor = textValue(selection, "awemeId");
                if (topic == null || !topic.equals(textValue(selection, "dataTopic"))
                        || anchor == null || !anchor.matches("[1-9][0-9]{0,29}")
                        || !textValue(material.path("acquisitionContract"), "configurationRevision").equals(textValue(selection, "configurationRevision")))
                    throw new IllegalArgumentException();
            }
            Set<String> expected = new HashSet<>(MetricSelection.dimensions());
            expected.addAll(selected);
            JsonNode rows = material.get("rows");
            if (rows == null || !rows.isArray()) throw new IllegalArgumentException();
            for (JsonNode row : rows) {
                if (!keys(row).equals(expected)) {
                    add(issues, ValidationIssueCode.ROW_FIELDS_MISMATCH, "materialDay.rows", "Row fields do not match the acquisition snapshot");
                    break;
                }
            }
            Set<String> observed = new HashSet<>();
            JsonNode pages = manifest.get("pages");
            if (pages == null || !pages.isArray()) throw new IllegalArgumentException();
            for (JsonNode page : pages) {
                String group = textValue(page, "group");
                if (group == null || !Set.of("base", "video", "assist", "ratio1", "ratio2").contains(group)) throw new IllegalArgumentException();
                observed.add(group);
                Long size = integralValue(page.get("page_info"), "page_size");
                if (size == null || size != ("base".equals(group) ? 100L : 200L)) throw new IllegalArgumentException();
            }
            if (!observed.equals(Set.of("base", "video", "assist", "ratio1", "ratio2"))) throw new IllegalArgumentException();
        } catch (Exception failure) {
            add(issues, ValidationIssueCode.ACQUISITION_CONTRACT_INVALID, "acquisitionContract",
                    "Acquisition catalog, selection snapshot or expected groups are invalid");
        }
    }

    private static Set<String> keys(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException();
        return node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }

    private void validateIdentityAndDate(
            FactInputValidationRequest request,
            JsonNode manifest,
            JsonNode material,
            List<ValidationIssue> issues
    ) {
        String expectedDate = request.expectedStatDate().toString();
        compareExpectedText(manifest, "advertiserId", request.expectedAdvertiserId(),
                ValidationIssueCode.ACCOUNT_ID_MISMATCH, "manifest.advertiserId", issues);
        compareExpectedText(material, "advertiserId", request.expectedAdvertiserId(),
                ValidationIssueCode.ACCOUNT_ID_MISMATCH, "materialDay.advertiserId", issues);
        compareExpectedText(manifest, "statDate", expectedDate,
                ValidationIssueCode.STAT_DATE_MISMATCH, "manifest.statDate", issues);
        compareExpectedText(material, "statDate", expectedDate,
                ValidationIssueCode.STAT_DATE_MISMATCH, "materialDay.statDate", issues);
    }

    private void validateMirroredMetadata(JsonNode manifest, JsonNode material, List<ValidationIssue> issues) {
        compareMirroredText(manifest, "projectRef", material, "projectRef",
                ValidationIssueCode.PROJECT_REF_MISMATCH, "projectRef", issues);
        compareMirroredText(manifest, "fieldCatalogVersion", material, "catalogVersion",
                ValidationIssueCode.CATALOG_VERSION_MISMATCH, "catalogVersion", issues);
        compareMirroredText(manifest, "publicationDataVersion", material, "dataVersion",
                ValidationIssueCode.PUBLICATION_VERSION_MISMATCH, "dataVersion", issues);
        compareMirroredText(manifest, "publicationDataHash", material, "dataHash",
                ValidationIssueCode.PUBLICATION_HASH_MISMATCH, "dataHash", issues);

        String hash = requiredText(material, "dataHash", "materialDay.dataHash", issues);
        if (hash != null && !SHA_256_PATTERN.matcher(hash).matches()) {
            add(issues, ValidationIssueCode.CONTRACT_FIELD_INVALID, "materialDay.dataHash",
                    "materialDay.dataHash must be a lowercase SHA-256 value");
        }

        JsonNode counts = requiredObject(material, "counts", "materialDay.counts", issues);
        if (counts == null) {
            return;
        }
        compareMirroredCount(manifest, "rawRowCount", counts, "rawRowCount",
                ValidationIssueCode.RAW_ROW_COUNT_MISMATCH, "rawRowCount", issues);
        compareMirroredCount(manifest, "reportRowCount", counts, "reportRowCount",
                ValidationIssueCode.ROW_COUNT_MISMATCH, "reportRowCount", issues);
        compareMirroredCount(manifest, "zeroSpendExcludedRows", counts, "zeroSpendExcludedRows",
                ValidationIssueCode.COUNT_MISMATCH, "zeroSpendExcludedRows", issues);
        compareMirroredCount(manifest, "zeroSpendRows", counts, "zeroSpendRows",
                ValidationIssueCode.COUNT_MISMATCH, "zeroSpendRows", issues);
        compareMirroredCount(manifest, "pageCount", counts, "pageCount",
                ValidationIssueCode.PAGE_COUNT_MISMATCH, "pageCount", issues);
    }

    private void validateRows(
            FactInputValidationRequest request,
            JsonNode manifest,
            JsonNode material,
            List<ValidationIssue> issues
    ) {
        JsonNode rows = material.get("rows");
        if (rows == null || !rows.isArray()) {
            add(issues, ValidationIssueCode.CONTRACT_FIELD_INVALID, "materialDay.rows",
                    "materialDay.rows must be an array");
            return;
        }

        Long manifestCount = requiredNonNegativeInteger(manifest, "reportRowCount",
                "manifest.reportRowCount", issues);
        JsonNode counts = material.get("counts");
        Long materialCount = counts != null && counts.isObject()
                ? requiredNonNegativeInteger(counts, "reportRowCount", "materialDay.counts.reportRowCount", issues)
                : null;
        long actualCount = rows.size();
        if ((manifestCount != null && manifestCount != actualCount)
                || (materialCount != null && materialCount != actualCount)) {
            add(issues, ValidationIssueCode.ROW_COUNT_MISMATCH, "reportRowCount",
                    "Reported row count does not equal materialDay.rows size");
        }

        String expectedDate = request.expectedStatDate().toString();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            JsonNode rowDate = row != null && row.isObject() ? row.get("stat_time_day") : null;
            if (rowDate == null || !rowDate.isString() || !expectedDate.equals(rowDate.stringValue())) {
                add(issues, ValidationIssueCode.ROW_DATE_MISMATCH,
                        "materialDay.rows[" + index + "].stat_time_day",
                        "Row statistic date does not equal the expected statistic date");
                break;
            }
        }

        String declaredHash = textValue(material, "dataHash");
        if (declaredHash == null) {
            return;
        }
        try {
            String computedHash = rowsHasher.sha256(rows);
            if (!declaredHash.equals(computedHash)) {
                add(issues, ValidationIssueCode.ROWS_HASH_MISMATCH, "materialDay.dataHash",
                        "materialDay.dataHash does not match the canonical rows content");
            }
        } catch (JacksonException exception) {
            add(issues, ValidationIssueCode.ROWS_HASH_MISMATCH, "materialDay.rows",
                    "Canonical rows hash could not be computed");
        }
    }

    private void validatePagination(JsonNode manifest, JsonNode material, List<ValidationIssue> issues) {
        if (!isTrue(manifest.get("paginationComplete"))) {
            add(issues, ValidationIssueCode.PAGINATION_INCOMPLETE, "manifest.paginationComplete",
                    "Manifest does not assert complete pagination");
        }
        if (!isTrue(material.get("paginationComplete"))) {
            add(issues, ValidationIssueCode.PAGINATION_INCOMPLETE, "materialDay.paginationComplete",
                    "Material data does not assert complete pagination");
        }

        JsonNode pages = manifest.get("pages");
        if (pages == null || !pages.isArray() || pages.isEmpty()) {
            add(issues, ValidationIssueCode.PAGINATION_INCOMPLETE, "manifest.pages",
                    "Manifest pages must be a non-empty array");
            return;
        }

        Long declaredPageCount = requiredNonNegativeInteger(manifest, "pageCount", "manifest.pageCount", issues);
        if (declaredPageCount != null && declaredPageCount != pages.size()) {
            add(issues, ValidationIssueCode.PAGE_COUNT_MISMATCH, "manifest.pageCount",
                    "Manifest page count does not equal manifest.pages size");
        }

        Map<String, GroupState> groups = new LinkedHashMap<>();
        long totalRawRows = 0;
        boolean pageStructureInvalid = false;
        for (int index = 0; index < pages.size(); index++) {
            JsonNode page = pages.get(index);
            if (page == null || !page.isObject()) {
                pageStructureInvalid = true;
                continue;
            }
            String group = textValue(page, "group");
            Long summaryPageNumber = integralValue(page, "page");
            Long code = integralValue(page, "code");
            Long rawRows = integralValue(page, "raw_rows");
            JsonNode pageInfo = page.get("page_info");
            if (group == null || group.isBlank() || summaryPageNumber == null || code == null
                    || rawRows == null || pageInfo == null || !pageInfo.isObject()) {
                pageStructureInvalid = true;
                continue;
            }
            Long pageNumber = integralValue(pageInfo, "page");
            Long pageSize = integralValue(pageInfo, "page_size");
            Long totalNumber = integralValue(pageInfo, "total_number");
            Long totalPages = integralValue(pageInfo, "total_page");
            JsonNode hasMoreNode = pageInfo.get("has_more");
            if (pageNumber == null || pageSize == null || totalNumber == null || totalPages == null
                    || hasMoreNode == null || !hasMoreNode.isBoolean()) {
                pageStructureInvalid = true;
                continue;
            }

            GroupState state = groups.computeIfAbsent(group, ignored -> new GroupState());
            long expectedPage = state.pagesSeen + 1;
            boolean expectedHasMore = pageNumber < totalPages;
            if (summaryPageNumber != pageNumber || pageNumber != expectedPage || code != 0
                    || totalPages < 0 || (totalPages == 0 && (pageNumber != 1 || totalNumber != 0 || rawRows != 0))
                    || (pageSize != 100 && pageSize != 200)
                    || totalNumber < 0 || rawRows < 0 || rawRows > pageSize
                    || hasMoreNode.booleanValue() != expectedHasMore
                    || (state.totalPages != null && !state.totalPages.equals(totalPages))
                    || (state.pageSize != null && !state.pageSize.equals(pageSize))
                    || (state.totalNumber != null && !state.totalNumber.equals(totalNumber))) {
                pageStructureInvalid = true;
            }
            state.pagesSeen++;
            state.totalPages = totalPages;
            state.pageSize = pageSize;
            state.totalNumber = totalNumber;
            state.rawRows += rawRows;
            totalRawRows += rawRows;
        }

        for (GroupState state : groups.values()) {
            if (state.totalPages == null || state.totalNumber == null
                    || state.pagesSeen != Math.max(1L, state.totalPages) || state.rawRows != state.totalNumber) {
                pageStructureInvalid = true;
            }
        }
        if (pageStructureInvalid) {
            add(issues, ValidationIssueCode.PAGINATION_INCOMPLETE, "manifest.pages",
                    "Page summaries are missing, unsuccessful, non-contiguous, or not closed");
        }

        Long manifestRawCount = requiredNonNegativeInteger(manifest, "rawRowCount",
                "manifest.rawRowCount", issues);
        JsonNode counts = material.get("counts");
        Long materialRawCount = counts != null && counts.isObject()
                ? requiredNonNegativeInteger(counts, "rawRowCount", "materialDay.counts.rawRowCount", issues)
                : null;
        if ((manifestRawCount != null && manifestRawCount != totalRawRows)
                || (materialRawCount != null && materialRawCount != totalRawRows)) {
            add(issues, ValidationIssueCode.RAW_ROW_COUNT_MISMATCH, "rawRowCount",
                    "Raw row count does not equal the sum of manifest page rows");
        }
    }

    private void compareFixedText(
            JsonNode root,
            String field,
            String expected,
            ValidationIssueCode code,
            String issueField,
            List<ValidationIssue> issues
    ) {
        String actual = requiredText(root, field, issueField, issues);
        if (actual != null && !expected.equals(actual)) {
            add(issues, code, issueField, issueField + " is not supported by this validator");
        }
    }

    private void compareExpectedText(
            JsonNode root,
            String field,
            String expected,
            ValidationIssueCode code,
            String issueField,
            List<ValidationIssue> issues
    ) {
        String actual = requiredText(root, field, issueField, issues);
        if (actual != null && !expected.equals(actual)) {
            add(issues, code, issueField, issueField + " does not equal the expected value");
        }
    }

    private void compareMirroredText(
            JsonNode left,
            String leftField,
            JsonNode right,
            String rightField,
            ValidationIssueCode code,
            String issueField,
            List<ValidationIssue> issues
    ) {
        String leftValue = requiredText(left, leftField, "manifest." + leftField, issues);
        String rightValue = requiredText(right, rightField, "materialDay." + rightField, issues);
        if (leftValue != null && rightValue != null && !leftValue.equals(rightValue)) {
            add(issues, code, issueField, "Manifest and material data values do not match");
        }
    }

    private void compareMirroredCount(
            JsonNode left,
            String leftField,
            JsonNode right,
            String rightField,
            ValidationIssueCode code,
            String issueField,
            List<ValidationIssue> issues
    ) {
        Long leftValue = requiredNonNegativeInteger(left, leftField, "manifest." + leftField, issues);
        Long rightValue = requiredNonNegativeInteger(right, rightField, "materialDay.counts." + rightField, issues);
        if (leftValue != null && rightValue != null && !leftValue.equals(rightValue)) {
            add(issues, code, issueField, "Manifest and material data counts do not match");
        }
    }

    private String requiredText(JsonNode root, String field, String issueField, List<ValidationIssue> issues) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            add(issues, ValidationIssueCode.CONTRACT_FIELD_INVALID, issueField,
                    issueField + " must be a non-blank string");
            return null;
        }
        return value.stringValue();
    }

    private JsonNode requiredObject(JsonNode root, String field, String issueField, List<ValidationIssue> issues) {
        JsonNode value = root.get(field);
        if (value == null || !value.isObject()) {
            add(issues, ValidationIssueCode.CONTRACT_FIELD_INVALID, issueField,
                    issueField + " must be an object");
            return null;
        }
        return value;
    }

    private Long requiredNonNegativeInteger(
            JsonNode root,
            String field,
            String issueField,
            List<ValidationIssue> issues
    ) {
        Long value = integralValue(root, field);
        if (value == null || value < 0) {
            add(issues, ValidationIssueCode.CONTRACT_FIELD_INVALID, issueField,
                    issueField + " must be a non-negative integer");
            return null;
        }
        return value;
    }

    private static String textValue(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static Integer integerValue(JsonNode root, String field) {
        Long value = integralValue(root, field);
        return value != null && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? value.intValue()
                : null;
    }

    private static Long integralValue(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                ? value.longValue()
                : null;
    }

    private static boolean isTrue(JsonNode value) {
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private static void add(
            List<ValidationIssue> issues,
            ValidationIssueCode code,
            String field,
            String message
    ) {
        issues.add(new ValidationIssue(code, field, message));
    }

    private FactInputValidationReceipt receipt(
            FactInputValidationRequest request,
            LoadedJson manifest,
            LoadedJson material,
            String projectRef,
            String dataHash,
            Integer pageCount,
            Integer reportRowCount,
            List<ValidationIssue> issues
    ) {
        return new FactInputValidationReceipt(
                request.runId(),
                issues.isEmpty() ? ValidationStatus.ACCEPTED : ValidationStatus.REJECTED,
                request.expectedAdvertiserId(),
                request.expectedStatDate(),
                projectRef,
                dataHash,
                pageCount,
                reportRowCount,
                manifest == null ? null : manifest.evidence(),
                material == null ? null : material.evidence(),
                issues);
    }

    private record LoadedJson(JsonNode root, InputFileEvidence evidence) {
    }

    private static final class GroupState {
        private long pagesSeen;
        private Long totalPages;
        private Long pageSize;
        private Long totalNumber;
        private long rawRows;
    }
}
