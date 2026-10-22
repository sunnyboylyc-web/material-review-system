package cn.datadashboard.ingest.persistence;

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
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualHistoryPreparedBatchReaderTest {

    private static final String RUN_ID = "manual-history-synthetic";
    private static final String ACCOUNT_ID = "1000000000000001";
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 9, 30);

    @TempDir
    Path temporaryDirectory;

    @TempDir
    Path outsideDirectory;

    private JsonMapper mapper;
    private ManualHistoryPreparedBatchReader reader;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
        reader = new ManualHistoryPreparedBatchReader();
    }

    @Test
    void preparesVerifiedManualHistoryAndPreservesZeroAndUnknown() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE",
                "NOT_IN_OVERLAP_SCOPE");

        PreparedFactBatch batch = reader.prepare(command(evidencePath));

        assertThat(batch.sourceType()).isEqualTo(FactSourceType.MANUAL_HISTORY);
        assertThat(batch.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(batch.statDate()).isEqualTo(STAT_DATE);
        assertThat(batch.rows()).hasSize(2);
        assertThat(batch.contentHash())
                .as("must match the Python converter's sorted compact UTF-8 JSON hash")
                .isEqualTo("b0be8a6f29fd0ba1fc6d5c8e9c6896f783661fe8a696038d438a947b9f6b6037");
        JsonNode first = mapper.readTree(batch.rows().getFirst().rawFactJson());
        assertThat(first.get("stat_cost_for_roi2").intValue()).isZero();
        assertThat(first.get("unavailable_metric").stringValue()).isEqualTo("UNKNOWN");
        assertThat(batch.manifestEvidence().absolutePath()).endsWith("source.xlsx");
        assertThat(batch.materialEvidence().absolutePath()).endsWith("facts.jsonl");
    }

    @Test
    void rejectsStagedUnverifiedBeforeDatabase() throws Exception {
        Path evidencePath = writeBundle("STAGED_UNVERIFIED", false, "MISSING", "UNVERIFIED",
                "NOT_IN_OVERLAP_SCOPE");

        assertThatThrownBy(() -> reader.prepare(command(evidencePath)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.MANUAL_HISTORY_NOT_READY);
    }

    @Test
    void rejectsAutomaticOverlapEvenIfOtherFlagsClaimReady() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE",
                "MATCHED_AUTO_POSITIVE_ROWS");

        assertThatThrownBy(() -> reader.prepare(command(evidencePath)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.MANUAL_HISTORY_NOT_READY);
    }

    @Test
    void rejectsKnownOverlapDatesEvenIfOverlapFlagClaimsOutsideScope() throws Exception {
        Path evidencePath = readyBundle();
        updateJson(evidencePath, day -> day.put("statDate", "2026-10-03"));

        assertRejected(evidencePath, FactImportErrorCode.MANUAL_HISTORY_NOT_READY);
    }

    @Test
    void rejectsReadyFlagsWithoutActualIdentityAttestation() throws Exception {
        Path evidencePath = readyBundle();
        updateJson(evidencePath, day -> day.set("accountIdentityEvidence",
                mapper.createObjectNode().put("status", "VERIFIED")));

        assertRejected(evidencePath, FactImportErrorCode.SOURCE_JSON_INVALID);
    }

    @Test
    void rejectsReusedAttestationsFromDifferentWorkbookAccountOrProject() throws Exception {
        for (String label : new String[]{"accountIdentityEvidence", "completenessEvidence"}) {
            for (String field : new String[]{"sourceWorkbookSha256", "accountId", "projectRef"}) {
                Path evidencePath = readyBundle();
                String wrongValue = field.equals("sourceWorkbookSha256") ? "a".repeat(64) : "different-source";
                updateAttestation(evidencePath, label, node -> node.put(field, wrongValue));

                assertRejected(evidencePath, FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH);
            }
        }
    }

    @Test
    void rejectsIdentityAttestationNotCoveringTheDay() throws Exception {
        Path evidencePath = readyBundle();
        updateAttestation(evidencePath, "accountIdentityEvidence", identity -> identity.put("validThrough", "2026-09-29"));

        assertRejected(evidencePath, FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH);
    }

    @Test
    void rejectsCompletenessForADifferentDayOrUnverifiedDay() throws Exception {
        Path evidencePath = readyBundle();
        updateAttestation(evidencePath, "completenessEvidence", completeness -> completeness.set("days",
                mapper.createObjectNode().put("2026-09-29", "COMPLETE")));
        assertRejected(evidencePath, FactImportErrorCode.SOURCE_JSON_INVALID);

        evidencePath = readyBundle();
        updateAttestation(evidencePath, "completenessEvidence", completeness -> completeness.set("days",
                mapper.createObjectNode().put(STAT_DATE.toString(), "UNVERIFIED")));
        assertRejected(evidencePath, FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH);
    }

    @Test
    void rejectsChangedAttestationBytesAndAttestationOutsideRoot() throws Exception {
        for (String label : new String[]{"accountIdentityEvidence", "completenessEvidence"}) {
            Path evidencePath = readyBundle();
            Path attestationPath = attestationPath(evidencePath, label);
            Files.writeString(attestationPath, "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            assertRejected(evidencePath, FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);

            evidencePath = readyBundle();
            Path outsidePath = outsideDirectory.resolve(label + ".json");
            Files.copy(attestationPath(evidencePath, label), outsidePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ObjectNode outsideEvidence = fileEvidence(outsidePath);
            updateJson(evidencePath, day -> day.set(label, outsideEvidence));
            assertRejected(evidencePath, FactImportErrorCode.SOURCE_READ_FAILED);
        }
    }

    @Test
    void rejectsUnconfirmedMethodOrMissingConfirmationReference() throws Exception {
        for (String label : new String[]{"accountIdentityEvidence", "completenessEvidence"}) {
            Path evidencePath = readyBundle();
            updateAttestation(evidencePath, label, node -> node.put("verificationMethod", "INFERRED_FROM_ROWS"));
            assertRejected(evidencePath, FactImportErrorCode.MANUAL_HISTORY_NOT_READY);

            evidencePath = readyBundle();
            updateAttestation(evidencePath, label, node -> node.put("evidenceRef", ""));
            assertRejected(evidencePath, FactImportErrorCode.SOURCE_JSON_INVALID);
        }
    }

    @Test
    void rejectsEmptyDayConfirmationForNonEmptyFacts() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE_NO_ROWS",
                "NOT_IN_OVERLAP_SCOPE");

        assertRejected(evidencePath, FactImportErrorCode.VALIDATION_RECEIPT_MISMATCH);
    }

    @Test
    void acceptsAnExplicitlyConfirmedEmptyDay() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE_NO_ROWS",
                "NOT_IN_OVERLAP_SCOPE", true);

        PreparedFactBatch batch = reader.prepare(command(evidencePath));
        assertThat(batch.rows()).isEmpty();
        assertThat(batch.contentHash()).isEqualTo(sha256("[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void preservesPythonScientificNotationDecimalAndLargeIntegerTokensExactly() throws Exception {
        Path evidencePath = readyBundle();
        String first = "{\"empty_list\":[],\"large_integer\":9007199254740993123456789,"
                + "\"material_id\":\"synthetic-1\",\"nullable_metric\":null,\"small_rate\":1e-07,"
                + "\"stat_cost_for_roi2\":0,\"stat_time_day\":\"2026-09-30\",\"unavailable_metric\":\"UNKNOWN\"}";
        String second = "{\"decimal_metric\":0.123456789012345678901,\"material_id\":\"synthetic-2\","
                + "\"stat_cost_for_roi2\":12.5,\"stat_time_day\":\"2026-09-30\"}";
        List<String> originalLines = List.of(first, second);
        replaceSyntheticFactLines(evidencePath, originalLines, true);

        PreparedFactBatch batch = reader.prepare(command(evidencePath));

        assertThat(batch.rows()).extracting(PreparedMaterialFact::rawFactJson).containsExactly(first, second);
        assertThat(batch.rows().getFirst().rowSha256()).isEqualTo(sha256(first.getBytes(StandardCharsets.UTF_8)));
        assertThat(batch.rows().getLast().rowSha256()).isEqualTo(sha256(second.getBytes(StandardCharsets.UTF_8)));
        assertThat(batch.contentHash()).isEqualTo(sha256(("[" + first + "," + second + "]")
                .getBytes(StandardCharsets.UTF_8)));
        assertThat(Files.readAllLines(evidencePath.getParent().resolve("facts.jsonl"), StandardCharsets.UTF_8))
                .containsExactlyElementsOf(originalLines);
    }

    @Test
    void rejectsEquivalentNumericSpellingWhenOnlyFileHashWasUpdated() throws Exception {
        Path evidencePath = readyBundle();
        List<String> changed = Files.readAllLines(evidencePath.getParent().resolve("facts.jsonl"), StandardCharsets.UTF_8)
                .stream().map(line -> line.replace("\"stat_cost_for_roi2\":12.5", "\"stat_cost_for_roi2\":1.25e+01"))
                .toList();
        replaceSyntheticFactLines(evidencePath, changed, false);

        assertRejected(evidencePath, FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);
    }

    private void replaceSyntheticFactLines(Path evidencePath, List<String> lines, boolean updateContentHash)
            throws Exception {
        Path facts = evidencePath.getParent().resolve("facts.jsonl");
        Files.writeString(facts, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        ObjectNode factsEvidence = fileEvidence(facts);
        String contentHash = sha256(("[" + String.join(",", lines) + "]").getBytes(StandardCharsets.UTF_8));
        updateJson(evidencePath, day -> {
            day.set("factsFileEvidence", factsEvidence);
            if (updateContentHash) {
                day.put("contentHash", contentHash);
            }
        });
    }

    @Test
    void rejectsWorkbookChangedAfterAttestation() throws Exception {
        Path evidencePath = readyBundle();
        Files.writeString(temporaryDirectory.resolve("sources/hash/source.xlsx"), "changed workbook",
                StandardCharsets.UTF_8);

        assertRejected(evidencePath, FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);
    }

    @Test
    void rejectsFactsChangedAfterEvidenceWasWritten() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE",
                "NOT_IN_OVERLAP_SCOPE");
        Files.writeString(evidencePath.getParent().resolve("facts.jsonl"), "{}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> reader.prepare(command(evidencePath)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);
    }

    @Test
    void rejectsPathsOutsideConfiguredManualHistoryRoot() throws Exception {
        Path evidencePath = writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE",
                "NOT_IN_OVERLAP_SCOPE");
        Path wrongRoot = temporaryDirectory.resolve("other-root");
        Files.createDirectories(wrongRoot);

        assertThatThrownBy(() -> reader.prepare(new ManualHistoryImportCommand(
                RUN_ID, evidencePath, wrongRoot)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(FactImportErrorCode.SOURCE_READ_FAILED);
    }

    private ManualHistoryImportCommand command(Path evidencePath) {
        return new ManualHistoryImportCommand(RUN_ID, evidencePath, temporaryDirectory);
    }

    private Path readyBundle() throws Exception {
        return writeBundle("READY_FOR_IMPORT", true, "VERIFIED", "COMPLETE", "NOT_IN_OVERLAP_SCOPE");
    }

    private void assertRejected(Path evidencePath, FactImportErrorCode expected) {
        assertThatThrownBy(() -> reader.prepare(command(evidencePath)))
                .isInstanceOf(FactPreparationException.class)
                .extracting(exception -> ((FactPreparationException) exception).errorCode())
                .isEqualTo(expected);
    }

    private void updateJson(Path path, Consumer<ObjectNode> change) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(Files.readAllBytes(path));
        change.accept(node);
        mapper.writeValue(path, node);
    }

    private Path attestationPath(Path evidencePath, String label) throws Exception {
        JsonNode day = mapper.readTree(Files.readAllBytes(evidencePath));
        return Path.of(day.get(label).get("absolutePath").stringValue());
    }

    private void updateAttestation(Path evidencePath, String label, Consumer<ObjectNode> change) throws Exception {
        Path attestationPath = attestationPath(evidencePath, label);
        updateJson(attestationPath, change);
        ObjectNode updatedEvidence = fileEvidence(attestationPath);
        updateJson(evidencePath, day -> day.set(label, updatedEvidence));
    }

    private Path writeBundle(
            String status,
            boolean eligible,
            String identityStatus,
            String completenessStatus,
            String overlapStatus
    ) throws Exception {
        return writeBundle(status, eligible, identityStatus, completenessStatus, overlapStatus, false);
    }

    private Path writeBundle(
            String status,
            boolean eligible,
            String identityStatus,
            String completenessStatus,
            String overlapStatus,
            boolean empty
    ) throws Exception {
        Path sourcePath = temporaryDirectory.resolve("sources/hash/source.xlsx");
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, "synthetic workbook evidence", StandardCharsets.UTF_8);

        Path dayDirectory = temporaryDirectory.resolve("derived/day");
        Files.createDirectories(dayDirectory);
        Path factsPath = dayDirectory.resolve("facts.jsonl");
        ArrayNode rows = mapper.createArrayNode();
        if (!empty) {
            rows.add(row("synthetic-1", 0));
            rows.add(row("synthetic-2", 12.5));
        }
        StringBuilder jsonl = new StringBuilder();
        rows.elements().forEach(row -> {
            try {
                jsonl.append(mapper.writeValueAsString(sortRecursively(row))).append('\n');
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        Files.writeString(factsPath, jsonl.toString(), StandardCharsets.UTF_8);

        Path identityPath = temporaryDirectory.resolve("identity.json");
        ObjectNode identity = attestation("manual-history-account-attestation-1.0.0", sourcePath);
        identity.put("validFrom", "2025-11-01");
        identity.put("validThrough", "2026-10-11");
        mapper.writeValue(identityPath, identity);
        Path completenessPath = temporaryDirectory.resolve("completeness.json");
        ObjectNode completeness = attestation("manual-history-completeness-attestation-1.0.0", sourcePath);
        completeness.set("days", mapper.createObjectNode().put(STAT_DATE.toString(), completenessStatus));
        mapper.writeValue(completenessPath, completeness);

        ObjectNode day = mapper.createObjectNode();
        day.put("schemaVersion", "manual-history-day-evidence-1.0.0");
        day.put("sourceType", "MANUAL_HISTORY");
        day.put("runId", RUN_ID);
        day.put("projectRef", "sampleproject");
        day.put("accountId", ACCOUNT_ID);
        day.put("accountIdentityStatus", identityStatus);
        day.set("accountIdentityEvidence", fileEvidence(identityPath));
        day.put("statDate", STAT_DATE.toString());
        day.put("status", status);
        day.put("databaseImportEligible", eligible);
        ArrayNode blockers = mapper.createArrayNode();
        if (!eligible) {
            blockers.add("MISSING_ACCOUNT_IDENTITY_EVIDENCE");
        }
        day.set("publicationBlockers", blockers);
        day.put("completenessStatus", completenessStatus);
        day.set("completenessEvidence", fileEvidence(completenessPath));
        day.put("mappingVersion", "synthetic-mapping-1");
        day.put("factSchemaVersion", "qianchuan-material-day-standard-1.0.0");
        day.put("catalogVersion", "synthetic-catalog-1");
        day.put("dataVersion", "synthetic-data-version");
        day.put("contentHash", sha256(mapper.writeValueAsBytes(sortRecursively(rows))));
        day.put("rowCount", rows.size());
        day.put("zeroSpendRowCount", empty ? 0 : 1);
        day.put("unknownMetricValueCount", 1);
        day.set("sourceWorkbookEvidence", fileEvidence(sourcePath));
        day.set("factsFileEvidence", fileEvidence(factsPath));
        day.put("mcpManifestStatus", "NOT_APPLICABLE_NOT_CREATED");
        day.set("overlap", mapper.createObjectNode().put("status", overlapStatus));

        Path evidencePath = dayDirectory.resolve("day-evidence.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidencePath, day);
        return evidencePath;
    }

    private ObjectNode attestation(String schema, Path sourcePath) throws Exception {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", schema);
        node.put("status", "VERIFIED");
        node.put("verificationMethod", "USER_CONFIRMED");
        node.put("evidenceRef", "synthetic-user-confirmation-reference");
        node.put("projectRef", "sampleproject");
        node.put("accountId", ACCOUNT_ID);
        node.put("sourceWorkbookSha256", sha256(Files.readAllBytes(sourcePath)));
        return node;
    }

    private ObjectNode row(String materialId, double spend) {
        ObjectNode row = mapper.createObjectNode();
        row.put("material_id", materialId);
        row.put("stat_time_day", STAT_DATE.toString());
        if (spend == 0) {
            row.put("stat_cost_for_roi2", 0);
        } else {
            row.put("stat_cost_for_roi2", spend);
        }
        row.put("unavailable_metric", "UNKNOWN");
        return row;
    }

    private ObjectNode fileEvidence(Path path) throws Exception {
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("absolutePath", path.toAbsolutePath().normalize().toString());
        evidence.put("sizeBytes", Files.size(path));
        evidence.put("sha256", sha256(Files.readAllBytes(path)));
        return evidence;
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
