package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.FactInputValidationReceipt;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;
import cn.datadashboard.ingest.validation.FactInputValidator;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
class PreparedFactBatchReader {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final String MATERIAL_FILE = "material_day.json";

    private final FactInputValidator validator;
    private final JsonMapper mapper;

    PreparedFactBatchReader() {
        this(new FactInputValidator(), JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    @Autowired
    PreparedFactBatchReader(FactInputValidator validator) {
        this(validator, JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    PreparedFactBatchReader(FactInputValidator validator, JsonMapper mapper) {
        this.validator = Objects.requireNonNull(validator);
        this.mapper = Objects.requireNonNull(mapper);
    }

    PreparedFactBatch prepare(VersionedFactImportCommand command) {
        FactInputValidationRequest request = command.validationRequest();
        FactInputValidationReceipt receipt = command.validationReceipt();
        verifyAcceptedReceipt(request, receipt);
        FactInputValidationReceipt freshReceipt = validator.validate(request);
        verifyFreshValidation(receipt, freshReceipt);
        receipt = freshReceipt;

        Path inputDirectory = request.inputDirectory().toAbsolutePath().normalize();
        Path manifestPath = inputDirectory.resolve(MANIFEST_FILE);
        Path materialPath = inputDirectory.resolve(MATERIAL_FILE);
        verifyEvidencePath(manifestPath, receipt.manifestFile(), "manifest");
        verifyEvidencePath(materialPath, receipt.materialDayFile(), "materialDay");

        byte[] manifestBytes = readSnapshot(manifestPath, "manifest");
        byte[] materialBytes = readSnapshot(materialPath, "materialDay");
        InputFileEvidence manifestEvidence = verifySnapshot(
                manifestPath, manifestBytes, receipt.manifestFile(), "manifest");
        InputFileEvidence materialEvidence = verifySnapshot(
                materialPath, materialBytes, receipt.materialDayFile(), "materialDay");

        JsonNode manifest = parseObject(manifestBytes, "manifest");
        JsonNode material = parseObject(materialBytes, "materialDay");
        String accountId = request.expectedAdvertiserId();
        LocalDate statDate = request.expectedStatDate();

        requireEqual(accountId, requiredString(manifest, "advertiserId", "manifest.advertiserId"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Manifest account changed after validation");
        requireEqual(accountId, requiredString(material, "advertiserId", "materialDay.advertiserId"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Material account changed after validation");
        requireEqual(statDate.toString(), requiredString(manifest, "statDate", "manifest.statDate"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Manifest date changed after validation");
        requireEqual(statDate.toString(), requiredString(material, "statDate", "materialDay.statDate"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Material date changed after validation");

        String projectRef = requiredString(material, "projectRef", "materialDay.projectRef");
        requireEqual(projectRef, requiredString(manifest, "projectRef", "manifest.projectRef"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Project references do not match");
        requireEqual(projectRef, receipt.observedProjectRef(),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Receipt project reference does not match snapshot");

        String contentHash = requiredString(material, "dataHash", "materialDay.dataHash");
        requireEqual(contentHash, requiredString(manifest, "publicationDataHash", "manifest.publicationDataHash"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Content hashes do not match");
        requireEqual(contentHash, receipt.observedDataHash(),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Receipt content hash does not match snapshot");

        String dataVersion = requiredString(material, "dataVersion", "materialDay.dataVersion");
        requireEqual(dataVersion, requiredString(manifest, "publicationDataVersion", "manifest.publicationDataVersion"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Data versions do not match");
        String schemaVersion = requiredString(material, "schemaVersion", "materialDay.schemaVersion");
        String catalogVersion = requiredString(material, "catalogVersion", "materialDay.catalogVersion");

        JsonNode rows = material.get("rows");
        if (rows == null || !rows.isArray()) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    "materialDay.rows must be an array");
        }
        if (receipt.reportRowCount() == null || receipt.reportRowCount() != rows.size()) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    "Receipt row count does not match snapshot");
        }

        List<PreparedMaterialFact> preparedRows = prepareRows(rows, statDate);
        return new PreparedFactBatch(
                request.runId(),
                projectRef,
                accountId,
                statDate,
                FactSourceType.MCP_AUTO,
                dataVersion,
                contentHash,
                schemaVersion,
                catalogVersion,
                manifestEvidence,
                materialEvidence,
                preparedRows);
    }

    private List<PreparedMaterialFact> prepareRows(JsonNode rows, LocalDate statDate) {
        List<PreparedMaterialFact> preparedRows = new ArrayList<>(rows.size());
        Set<String> materialIds = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (row == null || !row.isObject()) {
                throw invalidRow(index, "Row must be an object");
            }
            String materialId = requiredString(row, "material_id", "rows[" + index + "].material_id");
            if (!materialIds.add(materialId)) {
                throw invalidRow(index, "Duplicate material ID in one account day");
            }
            String rowDate = requiredString(row, "stat_time_day", "rows[" + index + "].stat_time_day");
            if (!statDate.toString().equals(rowDate)) {
                throw invalidRow(index, "Row date does not match the requested statistic date");
            }
            try {
                String rawJson = mapper.writeValueAsString(row);
                preparedRows.add(new PreparedMaterialFact(
                        materialId,
                        index + 1,
                        rawJson,
                        sha256(rawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            } catch (JacksonException exception) {
                throw new FactPreparationException(
                        FactImportErrorCode.FACT_ROW_INVALID,
                        "Row JSON could not be serialized",
                        exception);
            }
        }
        return List.copyOf(preparedRows);
    }

    private void verifyAcceptedReceipt(
            FactInputValidationRequest request,
            FactInputValidationReceipt receipt
    ) {
        if (!receipt.accepted()) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_NOT_ACCEPTED,
                    "Only accepted validation receipts can be imported");
        }
        if (!request.runId().equals(receipt.runId())
                || !request.expectedAdvertiserId().equals(receipt.expectedAdvertiserId())
                || !request.expectedStatDate().equals(receipt.expectedStatDate())
                || receipt.manifestFile() == null
                || receipt.materialDayFile() == null) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    "Validation request and receipt do not match");
        }
    }

    private void verifyFreshValidation(
            FactInputValidationReceipt supplied,
            FactInputValidationReceipt fresh
    ) {
        if (!fresh.accepted() || fresh.manifestFile() == null || fresh.materialDayFile() == null) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                    "Current source no longer passes input validation");
        }
        boolean manifestChanged = !sameEvidence(supplied.manifestFile(), fresh.manifestFile());
        boolean materialChanged = !sameEvidence(supplied.materialDayFile(), fresh.materialDayFile());
        if (manifestChanged || materialChanged) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                    "Source bytes changed after the supplied validation receipt");
        }
        if (!Objects.equals(supplied.runId(), fresh.runId())
                || !Objects.equals(supplied.expectedAdvertiserId(), fresh.expectedAdvertiserId())
                || !Objects.equals(supplied.expectedStatDate(), fresh.expectedStatDate())
                || !Objects.equals(supplied.observedProjectRef(), fresh.observedProjectRef())
                || !Objects.equals(supplied.observedDataHash(), fresh.observedDataHash())
                || !Objects.equals(supplied.pageCount(), fresh.pageCount())
                || !Objects.equals(supplied.reportRowCount(), fresh.reportRowCount())) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    "Supplied validation receipt does not match current validator output");
        }
    }

    private static boolean sameEvidence(InputFileEvidence left, InputFileEvidence right) {
        return Objects.equals(left.absolutePath(), right.absolutePath())
                && left.sizeBytes() == right.sizeBytes()
                && Objects.equals(left.sha256(), right.sha256());
    }

    private void verifyEvidencePath(Path expectedPath, InputFileEvidence evidence, String label) {
        Path recordedPath;
        try {
            recordedPath = Path.of(evidence.absolutePath()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    label + " evidence path is invalid",
                    exception);
        }
        if (!expectedPath.equals(recordedPath)) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    label + " evidence path does not match the validation request");
        }
    }

    private byte[] readSnapshot(Path path, String label) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_READ_FAILED,
                    label + " source could not be read",
                    exception);
        }
    }

    private InputFileEvidence verifySnapshot(
            Path path,
            byte[] bytes,
            InputFileEvidence expected,
            String label
    ) {
        String actualHash = sha256(bytes);
        if (expected.sizeBytes() != bytes.length || !expected.sha256().equals(actualHash)) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                    label + " source changed after validation");
        }
        return new InputFileEvidence(
                path.getFileName().toString(),
                path.toAbsolutePath().normalize().toString(),
                bytes.length,
                actualHash);
    }

    private JsonNode parseObject(byte[] bytes, String label) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new FactPreparationException(
                        FactImportErrorCode.SOURCE_JSON_INVALID,
                        label + " JSON root must be an object");
            }
            return root;
        } catch (JacksonException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " source is not valid unambiguous JSON",
                    exception);
        }
    }

    private static String requiredString(JsonNode root, String field, String label) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a non-blank string");
        }
        return value.stringValue();
    }

    private static void requireEqual(
            String expected,
            String actual,
            FactImportErrorCode code,
            String message
    ) {
        if (!Objects.equals(expected, actual)) {
            throw new FactPreparationException(code, message);
        }
    }

    private static FactPreparationException invalidRow(int index, String message) {
        return new FactPreparationException(
                FactImportErrorCode.FACT_ROW_INVALID,
                "rows[" + index + "]: " + message);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
