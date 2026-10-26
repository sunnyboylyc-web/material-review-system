package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Component
class ManualHistoryPreparedBatchReader {

    private static final String EVIDENCE_SCHEMA = "manual-history-day-evidence-1.0.0";
    private static final String IDENTITY_SCHEMA = "manual-history-account-attestation-1.0.0";
    private static final String COMPLETENESS_SCHEMA = "manual-history-completeness-attestation-1.0.0";
    private static final String READY_STATUS = "READY_FOR_IMPORT";
    private static final Set<String> COMPLETE_STATUSES = Set.of("COMPLETE", "COMPLETE_NO_ROWS");
    // The v1 sampleproject workbook contract reserves these days for the verified automatic archive.
    // A mutable day-evidence overlap flag cannot waive that source precedence.
    private static final LocalDate AUTO_OVERLAP_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate AUTO_OVERLAP_END = LocalDate.of(2026, 10, 8);

    private final JsonMapper mapper;

    ManualHistoryPreparedBatchReader() {
        this(JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build());
    }

    ManualHistoryPreparedBatchReader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    PreparedFactBatch prepare(ManualHistoryImportCommand command) {
        Path allowedRoot = normalizeExistingDirectory(command.allowedDataRoot(), "allowedDataRoot");
        Path evidencePath = normalizeExistingFile(command.dayEvidencePath(), allowedRoot, "dayEvidence");
        JsonNode dayEvidence = parseObject(readBytes(evidencePath, "dayEvidence"), "dayEvidence");

        requireEqual(EVIDENCE_SCHEMA, requiredString(dayEvidence, "schemaVersion", "schemaVersion"),
                FactImportErrorCode.SOURCE_JSON_INVALID, "Manual history evidence schema is unsupported");
        requireEqual("MANUAL_HISTORY", requiredString(dayEvidence, "sourceType", "sourceType"),
                FactImportErrorCode.SOURCE_JSON_INVALID, "Manual history source type is invalid");
        requireEqual(command.runId(), requiredString(dayEvidence, "runId", "runId"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH, "Run ID does not match day evidence");
        requireEqual(READY_STATUS, requiredString(dayEvidence, "status", "status"),
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY,
                "Manual history day is not READY_FOR_IMPORT");
        if (!requiredBoolean(dayEvidence, "databaseImportEligible", "databaseImportEligible")) {
            throw notReady("Manual history day is not eligible for database import");
        }
        JsonNode blockers = dayEvidence.get("publicationBlockers");
        if (blockers == null || !blockers.isArray() || !blockers.isEmpty()) {
            throw notReady("Manual history day has publication blockers");
        }
        requireEqual("VERIFIED", requiredString(dayEvidence, "accountIdentityStatus", "accountIdentityStatus"),
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY,
                "Manual history account identity is not verified");
        String completeness = requiredString(dayEvidence, "completenessStatus", "completenessStatus");
        if (!COMPLETE_STATUSES.contains(completeness)) {
            throw notReady("Manual history day completeness is not verified");
        }
        requireEqual("NOT_APPLICABLE_NOT_CREATED",
                requiredString(dayEvidence, "mcpManifestStatus", "mcpManifestStatus"),
                FactImportErrorCode.SOURCE_JSON_INVALID,
                "Manual history evidence must not claim an MCP manifest");
        JsonNode overlap = requiredObject(dayEvidence, "overlap", "overlap");
        requireEqual("NOT_IN_OVERLAP_SCOPE", requiredString(overlap, "status", "overlap.status"),
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY,
                "Automatic overlap days cannot be imported from manual history");

        String accountId = requiredString(dayEvidence, "accountId", "accountId");
        String projectRef = requiredString(dayEvidence, "projectRef", "projectRef");
        LocalDate statDate = requiredDate(dayEvidence, "statDate", "statDate");
        if ("sampleproject".equals(projectRef)
                && !statDate.isBefore(AUTO_OVERLAP_START) && !statDate.isAfter(AUTO_OVERLAP_END)) {
            throw notReady("Known automatic archive days cannot be imported from the v1 manual workbook");
        }
        String dataVersion = requiredString(dayEvidence, "dataVersion", "dataVersion");
        String contentHash = requiredSha(dayEvidence, "contentHash", "contentHash");
        String schemaVersion = requiredString(dayEvidence, "factSchemaVersion", "factSchemaVersion");
        String catalogVersion = requiredString(dayEvidence, "catalogVersion", "catalogVersion");
        int expectedRows = requiredNonNegativeInt(dayEvidence, "rowCount", "rowCount");
        int expectedZeroRows = requiredNonNegativeInt(dayEvidence, "zeroSpendRowCount", "zeroSpendRowCount");

        InputFileEvidence sourceEvidence = readAndVerifyEvidence(
                requiredObject(dayEvidence, "sourceWorkbookEvidence", "sourceWorkbookEvidence"),
                allowedRoot,
                null,
                "sourceWorkbookEvidence").evidence();
        verifyIdentityAttestation(dayEvidence, allowedRoot, sourceEvidence.sha256(), projectRef, accountId, statDate);
        verifyCompletenessAttestation(dayEvidence, allowedRoot, sourceEvidence.sha256(), projectRef,
                accountId, statDate, completeness, expectedRows);
        Path expectedFactsPath = evidencePath.getParent().resolve("facts.jsonl").normalize();
        InputFileEvidence factsEvidence = readAndVerifyEvidence(
                requiredObject(dayEvidence, "factsFileEvidence", "factsFileEvidence"),
                allowedRoot,
                expectedFactsPath,
                "factsFileEvidence").evidence();
        Path factsPath = Path.of(factsEvidence.absolutePath()).toAbsolutePath().normalize();
        List<PreparedMaterialFact> rows = prepareRows(factsPath, statDate);
        if (rows.size() != expectedRows) {
            throw invalidRow("Manual history facts row count does not match day evidence");
        }
        int actualZeroRows = countZeroSpend(rows);
        if (actualZeroRows != expectedZeroRows) {
            throw invalidRow("Manual history zero-spend count does not match day evidence");
        }
        String reconstructedHash = contentHash(rows);
        if (!contentHash.equals(reconstructedHash)) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                    "Manual history content hash does not match the verified rows");
        }

        return new PreparedFactBatch(
                command.runId(),
                projectRef,
                accountId,
                statDate,
                FactSourceType.MANUAL_HISTORY,
                dataVersion,
                contentHash,
                schemaVersion,
                catalogVersion,
                sourceEvidence,
                factsEvidence,
                rows);
    }

    private void verifyIdentityAttestation(
            JsonNode dayEvidence, Path allowedRoot, String sourceHash,
            String projectRef, String accountId, LocalDate statDate
    ) {
        JsonNode identity = readAttestation(dayEvidence, "accountIdentityEvidence", IDENTITY_SCHEMA,
                allowedRoot, sourceHash, projectRef, accountId);
        LocalDate validFrom = requiredDate(identity, "validFrom", "identity.validFrom");
        LocalDate validThrough = requiredDate(identity, "validThrough", "identity.validThrough");
        if (statDate.isBefore(validFrom) || statDate.isAfter(validThrough)) {
            throw new FactPreparationException(FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    "Account identity attestation does not cover this statistics date");
        }
    }

    private void verifyCompletenessAttestation(
            JsonNode dayEvidence, Path allowedRoot, String sourceHash,
            String projectRef, String accountId, LocalDate statDate, String completeness, int rowCount
    ) {
        JsonNode attestation = readAttestation(dayEvidence, "completenessEvidence", COMPLETENESS_SCHEMA,
                allowedRoot, sourceHash, projectRef, accountId);
        JsonNode days = requiredObject(attestation, "days", "completeness.days");
        requireEqual(completeness, requiredString(days, statDate.toString(), "completeness.days[statDate]"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                "Completeness attestation does not confirm this day status");
        if ((rowCount == 0) != "COMPLETE_NO_ROWS".equals(completeness)) {
            throw new FactPreparationException(FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    "Completeness status does not match whether the day has fact rows");
        }
    }

    private JsonNode readAttestation(
            JsonNode dayEvidence, String label, String schema,
            Path allowedRoot, String sourceHash, String projectRef, String accountId
    ) {
        VerifiedFile verified = readAndVerifyEvidence(requiredObject(dayEvidence, label, label),
                allowedRoot, null, label);
        JsonNode attestation = parseObject(verified.bytes(), label);
        requireEqual(schema, requiredString(attestation, "schemaVersion", label + ".schemaVersion"),
                FactImportErrorCode.SOURCE_JSON_INVALID, "Manual history attestation schema is unsupported");
        requireEqual("VERIFIED", requiredString(attestation, "status", label + ".status"),
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY, "Manual history attestation is not verified");
        requireEqual("USER_CONFIRMED", requiredString(attestation, "verificationMethod", label + ".verificationMethod"),
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY, "Manual history confirmation method is unsupported");
        requiredString(attestation, "evidenceRef", label + ".evidenceRef");
        requireEqual(sourceHash, requiredSha(attestation, "sourceWorkbookSha256", label + ".sourceWorkbookSha256"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                "Manual history attestation belongs to a different source workbook");
        requireEqual(projectRef, requiredString(attestation, "projectRef", label + ".projectRef"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                "Manual history attestation belongs to a different project");
        requireEqual(accountId, requiredString(attestation, "accountId", label + ".accountId"),
                FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                "Manual history attestation belongs to a different account");
        return attestation;
    }

    private List<PreparedMaterialFact> prepareRows(Path path, LocalDate statDate) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_READ_FAILED,
                    "Manual history facts could not be read",
                    exception);
        }
        List<PreparedMaterialFact> rows = new ArrayList<>(lines.size());
        Set<String> materialIds = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                throw invalidRow("Manual history facts contain a blank JSONL record");
            }
            JsonNode row;
            try {
                row = mapper.readTree(line);
            } catch (JacksonException exception) {
                throw new FactPreparationException(
                        FactImportErrorCode.SOURCE_JSON_INVALID,
                        "Manual history facts contain invalid JSON",
                        exception);
            }
            if (row == null || !row.isObject()) {
                throw invalidRow("Manual history fact row must be a JSON object");
            }
            String materialId = requiredString(row, "material_id", "facts.material_id");
            if (!materialIds.add(materialId)) {
                throw invalidRow("Duplicate material ID in one manual history account day");
            }
            requireEqual(statDate.toString(), requiredString(row, "stat_time_day", "facts.stat_time_day"),
                    FactImportErrorCode.FACT_ROW_INVALID,
                    "Manual history fact date does not match day evidence");
            // The v1 producer has already serialized canonical Python JSON per line.
            // Re-serialization would alter numeric tokens such as 1e-07 or large decimals.
            rows.add(new PreparedMaterialFact(
                    materialId,
                    index + 1,
                    line,
                    sha256(line.getBytes(StandardCharsets.UTF_8))));
        }
        return List.copyOf(rows);
    }

    private int countZeroSpend(List<PreparedMaterialFact> rows) {
        int count = 0;
        for (PreparedMaterialFact row : rows) {
            JsonNode node;
            try {
                node = mapper.readTree(row.rawFactJson());
            } catch (JacksonException exception) {
                throw invalidRow("Prepared manual history row could not be read back");
            }
            JsonNode spend = node.get("stat_cost_for_roi2");
            if (spend == null || !spend.isNumber()) {
                throw invalidRow("Manual history spend must be numeric");
            }
            if (spend.decimalValue().signum() < 0) {
                throw invalidRow("Manual history spend cannot be negative");
            }
            if (spend.decimalValue().signum() == 0) {
                count++;
            }
        }
        return count;
    }

    private String contentHash(List<PreparedMaterialFact> rows) {
        // facts_file_bytes and daily_content_hash use the same Python json_bytes function.
        // Preserve its exact UTF-8 row tokens, joining with array punctuation only.
        String array = "[" + String.join(",", rows.stream().map(PreparedMaterialFact::rawFactJson).toList()) + "]";
        return sha256(array.getBytes(StandardCharsets.UTF_8));
    }

    private VerifiedFile readAndVerifyEvidence(
            JsonNode node,
            Path allowedRoot,
            Path expectedPath,
            String label
    ) {
        String absolutePath = requiredString(node, "absolutePath", label + ".absolutePath");
        long expectedSize = requiredNonNegativeLong(node, "sizeBytes", label + ".sizeBytes");
        String expectedHash = requiredSha(node, "sha256", label + ".sha256");
        Path path = normalizeExistingFile(Path.of(absolutePath), allowedRoot, label);
        if (expectedPath != null && !path.equals(normalizeExistingFile(expectedPath, allowedRoot, label))) {
            throw new FactPreparationException(
                    FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH,
                    label + " path does not match the day bundle layout");
        }
        byte[] bytes = readBytes(path, label);
        if (bytes.length != expectedSize || !sha256(bytes).equals(expectedHash)) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                    label + " bytes do not match the recorded evidence");
        }
        return new VerifiedFile(new InputFileEvidence(
                path.getFileName().toString(), path.toString(), expectedSize, expectedHash), bytes);
    }

    private static Path normalizeExistingDirectory(Path candidate, String label) {
        Path path = realPath(candidate, label);
        if (!Files.isDirectory(path)) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_READ_FAILED,
                    label + " is not an existing directory");
        }
        return path;
    }

    private static Path normalizeExistingFile(Path candidate, Path allowedRoot, String label) {
        Path path = realPath(candidate, label);
        if (!path.startsWith(allowedRoot) || !Files.isRegularFile(path)) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_READ_FAILED,
                    label + " is outside the allowed data root or is not a regular file");
        }
        return path;
    }

    private static Path realPath(Path candidate, String label) {
        try {
            return candidate.toRealPath();
        } catch (IOException exception) {
            throw new FactPreparationException(FactImportErrorCode.SOURCE_READ_FAILED,
                    label + " could not be resolved to an existing path", exception);
        }
    }

    private record VerifiedFile(InputFileEvidence evidence, byte[] bytes) { }

    private static byte[] readBytes(Path path, String label) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_READ_FAILED,
                    label + " could not be read",
                    exception);
        }
    }

    private JsonNode parseObject(byte[] bytes, String label) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new FactPreparationException(
                        FactImportErrorCode.SOURCE_JSON_INVALID,
                        label + " must be a JSON object");
            }
            return node;
        } catch (JacksonException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " contains invalid JSON",
                    exception);
        }
    }

    private static JsonNode requiredObject(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a JSON object");
        }
        return value;
    }

    private static String requiredString(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a non-blank string");
        }
        return value.stringValue();
    }

    private static String requiredSha(JsonNode parent, String field, String label) {
        String value = requiredString(parent, field, label);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a lowercase SHA-256 value");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static int requiredNonNegativeInt(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a non-negative integer");
        }
        return value.intValue();
    }

    private static long requiredNonNegativeLong(JsonNode parent, String field, String label) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static LocalDate requiredDate(JsonNode parent, String field, String label) {
        String value = requiredString(parent, field, label);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new FactPreparationException(
                    FactImportErrorCode.SOURCE_JSON_INVALID,
                    label + " must be an ISO date",
                    exception);
        }
    }

    private static void requireEqual(
            String expected,
            String actual,
            FactImportErrorCode errorCode,
            String message
    ) {
        if (!expected.equals(actual)) {
            throw new FactPreparationException(errorCode, message);
        }
    }

    private static FactPreparationException notReady(String message) {
        return new FactPreparationException(FactImportErrorCode.MANUAL_HISTORY_NOT_READY, message);
    }

    private static FactPreparationException invalidRow(String message) {
        return new FactPreparationException(FactImportErrorCode.FACT_ROW_INVALID, message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
