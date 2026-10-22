package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredFactVersionVerifierTest {

    private static final String ACCOUNT_ID = "1000000000000001";
    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 11);

    private final StoredFactVersionVerifier verifier = new StoredFactVersionVerifier();

    @Test
    void acceptsSameContentIdentityWhenNewFilesHaveDifferentBytesButRowsKeepSameOrderAndMeaning() {
        PreparedFactBatch incoming = incomingBatchWithReformattedRows();
        StoredFactVersionSnapshot stored = storedVersion();
        List<StoredMaterialFactSnapshot> storedFacts = storedFacts();

        assertThat(incoming.manifestEvidence().sha256()).isNotEqualTo(stored.manifestSha256());
        assertThat(incoming.materialEvidence().sha256()).isNotEqualTo(stored.materialSha256());
        assertThat(incoming.rows()).extracting(PreparedMaterialFact::materialId)
                .containsExactly("material-1", "material-2");
        assertThat(storedFacts).extracting(StoredMaterialFactSnapshot::materialId)
                .containsExactly("material-1", "material-2");

        assertThatCode(() -> verifier.verify(incoming, stored, storedFacts))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsIdempotentHitWhenStoredPayloadNoLongerMatchesItsOwnRowHash() {
        List<StoredMaterialFactSnapshot> corruptFacts = List.of(
                new StoredMaterialFactSnapshot(
                        ACCOUNT_ID,
                        STAT_DATE,
                        "material-1",
                        1,
                        "{\"material_id\":\"material-1\",\"metric\":999}",
                        sha256("{\"material_id\":\"material-1\",\"metric\":0}")),
                storedFacts().get(1));

        assertThatThrownBy(() -> verifier.verify(incomingBatchWithReformattedRows(), storedVersion(), corruptFacts))
                .isInstanceOf(ReadbackVerificationException.class);
    }

    @Test
    void rejectsStoredRowsThatAreSelfHashedButNoLongerReconstructContentHash() {
        String changed = "{\"material_id\":\"material-1\",\"metric\":999}";
        List<StoredMaterialFactSnapshot> changedFacts = List.of(
                new StoredMaterialFactSnapshot(
                        ACCOUNT_ID, STAT_DATE, "material-1", 1, changed, sha256(changed)),
                storedFacts().get(1));

        assertThatThrownBy(() -> verifier.verify(incomingBatchWithReformattedRows(), storedVersion(), changedFacts))
                .isInstanceOf(ReadbackVerificationException.class)
                .hasMessageContaining("content hash");
    }

    @Test
    void manualIdempotentReadbackPreservesScientificNotationDecimalLargeIntegerAndUnknown() {
        List<StoredMaterialFactSnapshot> facts = manualFacts();
        PreparedFactBatch incoming = manualIncoming(facts);

        assertThatCode(() -> verifier.verify(incoming, manualStored(incoming), facts)).doesNotThrowAnyException();
        assertThat(incoming.contentHash()).isNotEqualTo(canonicalContentHash(facts));
    }

    @Test
    void manualIdempotentReadbackRejectsEquivalentNumbersWithDifferentByteSpelling() {
        List<StoredMaterialFactSnapshot> original = manualFacts();
        PreparedFactBatch incoming = manualIncoming(original);
        StoredMaterialFactSnapshot first = original.getFirst();
        String rewritten = first.rawFactJson().replace("1e-07", "1.0E-7");
        List<StoredMaterialFactSnapshot> changed = List.of(new StoredMaterialFactSnapshot(
                first.accountId(), first.statDate(), first.materialId(), first.sourceRowNumber(),
                rewritten, sha256(rewritten)), original.getLast());

        assertThatThrownBy(() -> verifier.verify(incoming, manualStored(incoming), changed))
                .isInstanceOf(ReadbackVerificationException.class).hasMessageContaining("content hash");
    }

    @Test
    void manualIdempotentReadbackRejectsReorderedRowsEvenWithValidIndividualHashes() {
        List<StoredMaterialFactSnapshot> original = manualFacts();
        PreparedFactBatch incoming = manualIncoming(original);
        StoredMaterialFactSnapshot first = original.getFirst();
        StoredMaterialFactSnapshot last = original.getLast();
        List<StoredMaterialFactSnapshot> changed = List.of(
                new StoredMaterialFactSnapshot(last.accountId(), last.statDate(), last.materialId(), 1,
                        last.rawFactJson(), last.rowSha256()),
                new StoredMaterialFactSnapshot(first.accountId(), first.statDate(), first.materialId(), 2,
                        first.rawFactJson(), first.rowSha256()));

        assertThatThrownBy(() -> verifier.verify(incoming, manualStored(incoming), changed))
                .isInstanceOf(ReadbackVerificationException.class).hasMessageContaining("content hash");
    }

    @Test
    void manualIdempotentReadbackAcceptsExplicitEmptyArrayContentHash() {
        PreparedFactBatch incoming = manualIncoming(List.of());
        assertThat(incoming.contentHash()).isEqualTo(sha256("[]"));
        assertThatCode(() -> verifier.verify(incoming, manualStored(incoming), List.of()))
                .doesNotThrowAnyException();
    }

    private List<StoredMaterialFactSnapshot> manualFacts() {
        String first = "{\"empty_list\":[],\"large_integer\":9007199254740993123456789,"
                + "\"material_id\":\"manual-1\",\"nullable_metric\":null,\"rate\":1e-07,\"unknown_metric\":\"UNKNOWN\"}";
        String second = "{\"decimal_metric\":0.123456789012345678901,\"material_id\":\"manual-2\",\"spend\":0}";
        return List.of(new StoredMaterialFactSnapshot(ACCOUNT_ID, STAT_DATE, "manual-1", 1, first, sha256(first)),
                new StoredMaterialFactSnapshot(ACCOUNT_ID, STAT_DATE, "manual-2", 2, second, sha256(second)));
    }

    private PreparedFactBatch manualIncoming(List<StoredMaterialFactSnapshot> facts) {
        String contentHash = sha256("[" + String.join(",", facts.stream()
                .map(StoredMaterialFactSnapshot::rawFactJson).toList()) + "]");
        return new PreparedFactBatch("synthetic-manual-replay", "synthetic-project", ACCOUNT_ID, STAT_DATE,
                FactSourceType.MANUAL_HISTORY, "manual-v1", contentHash, "manual-schema", "manual-catalog",
                new InputFileEvidence("source.xlsx", "D:\\synthetic\\source.xlsx", 1, "b".repeat(64)),
                new InputFileEvidence("facts.jsonl", "D:\\synthetic\\facts.jsonl", 2, "c".repeat(64)),
                facts.stream().map(fact -> new PreparedMaterialFact(fact.materialId(), fact.sourceRowNumber(),
                        fact.rawFactJson(), fact.rowSha256())).toList());
    }

    private StoredFactVersionSnapshot manualStored(PreparedFactBatch incoming) {
        return new StoredFactVersionSnapshot(ACCOUNT_ID, STAT_DATE, incoming.contentHash(),
                FactVersionDisposition.CURRENT, incoming.rows().size(), "D:\\synthetic\\source.xlsx", 1,
                "b".repeat(64), "D:\\synthetic\\facts.jsonl", 2, "c".repeat(64));
    }

    private PreparedFactBatch incomingBatchWithReformattedRows() {
        String first = "{\"metric\":0,\"material_id\":\"material-1\"}";
        String second = "{\"metric\":1,\"material_id\":\"material-2\"}";
        return new PreparedFactBatch(
                "run-reformatted",
                "synthetic-project",
                ACCOUNT_ID,
                STAT_DATE,
                FactSourceType.MCP_AUTO,
                "same-content-new-metadata",
                canonicalContentHash(storedFacts()),
                "schema-v1",
                "catalog-v1",
                new InputFileEvidence("manifest.json", "D:\\synthetic\\new\\manifest.json", 301, "d".repeat(64)),
                new InputFileEvidence("material_day.json", "D:\\synthetic\\new\\material_day.json", 701, "e".repeat(64)),
                List.of(
                        new PreparedMaterialFact("material-1", 1, first, sha256(first)),
                        new PreparedMaterialFact("material-2", 2, second, sha256(second))));
    }

    private StoredFactVersionSnapshot storedVersion() {
        return new StoredFactVersionSnapshot(
                ACCOUNT_ID,
                STAT_DATE,
                canonicalContentHash(storedFacts()),
                FactVersionDisposition.CURRENT,
                2,
                "D:\\synthetic\\original\\manifest.json",
                250,
                "b".repeat(64),
                "D:\\synthetic\\original\\material_day.json",
                650,
                "c".repeat(64));
    }

    private List<StoredMaterialFactSnapshot> storedFacts() {
        String first = "{\"material_id\":\"material-1\",\"metric\":0}";
        String second = "{\"material_id\":\"material-2\",\"metric\":1}";
        return List.of(
                new StoredMaterialFactSnapshot(
                        ACCOUNT_ID, STAT_DATE, "material-1", 1, first, sha256(first)),
                new StoredMaterialFactSnapshot(
                        ACCOUNT_ID, STAT_DATE, "material-2", 2, second, sha256(second)));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value);
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String canonicalContentHash(List<StoredMaterialFactSnapshot> facts) {
        try {
            JsonMapper mapper = JsonMapper.builder().build();
            ArrayNode rows = mapper.createArrayNode();
            for (StoredMaterialFactSnapshot fact : facts) {
                rows.add(mapper.readTree(fact.rawFactJson()));
            }
            return sha256(mapper.writeValueAsBytes(sortRecursively(mapper, rows)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode sortRecursively(JsonMapper mapper, JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            node.properties().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey()))
                    .forEach(entry -> sorted.set(entry.getKey(), sortRecursively(mapper, entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            ((ArrayNode) node).elements()
                    .forEach(element -> sorted.add(sortRecursively(mapper, element)));
            return sorted;
        }
        return node.deepCopy();
    }
}
