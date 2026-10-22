package cn.datadashboard.local;

import cn.datadashboard.ingest.persistence.FactImportStore;
import cn.datadashboard.ingest.persistence.FactSourceType;
import cn.datadashboard.ingest.persistence.JdbcFactImportStore;
import cn.datadashboard.ingest.persistence.PreparedFactBatch;
import cn.datadashboard.ingest.persistence.PreparedMaterialFact;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFactVerifyMainTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ACCOUNT = "1000000000000001";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String RAW = "{\"material_id\":\"synthetic-1\",\"stat_time_day\":\"2026-09-30\",\"spend\":0,\"metric\":\"UNKNOWN\"}";
    private static final InputFileEvidence EVIDENCE = new InputFileEvidence("fixture", "synthetic-fixture", 0, "a".repeat(64));

    @Test
    void readsBackEquivalentCanonicalJsonAndPreservesZeroAndUnknown() throws Exception {
        PreparedFactBatch input = input(List.of(RAW), FactSourceType.MCP_AUTO);
        String differentlyFormatted = "{ \"metric\" : \"UNKNOWN\",\"spend\":0,\"stat_time_day\":\"2026-09-30\",\"material_id\":\"synthetic-1\" }";
        var stored = new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(version(input, "CURRENT", 1)),
                List.of(fact(differentlyFormatted)));

        assertThatCode(() -> LocalFactVerifyMain.compare(input, stored)).doesNotThrowAnyException();
    }

    @Test
    void manualHistoryPreservesPythonScientificNotationAndExactSourceLineBytes() throws Exception {
        String pythonLine = RAW.replace("\"spend\":0", "\"spend\":1e-07");
        PreparedFactBatch input = input(List.of(pythonLine), FactSourceType.MANUAL_HISTORY);
        var stored = new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(version(input, "CURRENT", 1)),
                List.of(fact(pythonLine)));
        assertThatCode(() -> LocalFactVerifyMain.compare(input, stored)).doesNotThrowAnyException();

        String equivalentButNotOriginal = pythonLine.replace("1e-07", "1.0E-7");
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(version(input, "CURRENT", 1)),
                List.of(fact(equivalentButNotOriginal))), "FACT_JSON_MISMATCH");
    }

    @Test
    void rejectsChangedBusinessValueEvenWhenTheStoredRowHashWasRecomputed() throws Exception {
        PreparedFactBatch input = input(List.of(RAW), FactSourceType.MCP_AUTO);
        for (String changed : List.of(RAW.replace("\"spend\":0", "\"spend\":null"),
                RAW.replace("\"UNKNOWN\"", "0"))) {
            var stored = new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(version(input, "CURRENT", 1)), List.of(fact(changed)));
            assertFailure(input, stored, "FACT_JSON_MISMATCH");
        }
    }

    @Test
    void rejectsWrongCurrentAndPreviousPointersAndWrongSource() throws Exception {
        PreparedFactBatch input = input(List.of(RAW), FactSourceType.MCP_AUTO);
        var correct = version(input, "CURRENT", 1);
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(8L, null, List.of(correct), List.of(fact(RAW))),
                "CURRENT_VERSION_MISMATCH");
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(7L, 8L, List.of(correct), List.of(fact(RAW))),
                "PREVIOUS_VERSION_MISMATCH");
        var manual = new LocalFactVerifyMain.Version(7, ACCOUNT, DATE, "sampleproject", "MANUAL_HISTORY", input.contentHash(),
                "CURRENT", 1, EVIDENCE, EVIDENCE);
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(manual), List.of(fact(RAW))),
                "VERSION_IDENTITY_OR_SOURCE_MISMATCH");
    }

    @Test
    void rejectsRowCountAndRawByteHashMismatch() throws Exception {
        PreparedFactBatch input = input(List.of(RAW), FactSourceType.MCP_AUTO);
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(7L, null,
                List.of(version(input, "CURRENT", 2)), List.of(fact(RAW))), "FACT_COUNT_MISMATCH");
        var wrongHash = new LocalFactVerifyMain.Fact(7, ACCOUNT, DATE, "synthetic-1", 1, RAW, "a".repeat(64));
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(7L, null,
                List.of(version(input, "CURRENT", 1)), List.of(wrongHash)), "FACT_BYTES_HASH_MISMATCH");
    }

    @Test
    void verifiesAnEmptyDayHasItsOwnCurrentVersionAndEmptyContentHash() throws Exception {
        PreparedFactBatch input = input(List.of(), FactSourceType.MANUAL_HISTORY);
        var stored = new LocalFactVerifyMain.DaySnapshot(7L, null, List.of(version(input, "CURRENT", 0)), List.of());

        assertThatCode(() -> LocalFactVerifyMain.compare(input, stored)).doesNotThrowAnyException();
        assertFailure(input, new LocalFactVerifyMain.DaySnapshot(null, null, List.of(), List.of()), "POINTER_MISMATCH");
    }

    @Test
    void inputPreparationContextHasOnlyAMemoryStoreAndNoDatabaseWriters() {
        var capture = new LocalFactVerifyMain.CaptureStore();
        try (var context = LocalFactVerifyMain.inputContext(capture)) {
            assertThat(context.getBean(FactImportStore.class)).isSameAs(capture);
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(JdbcFactImportStore.class)).isEmpty();
        }
    }

    @Test
    void databaseUrlIsLoopbackOnlyWithServerReadOnlyPropagation() {
        var plan = new LocalFactImportMain.ImportPlan(Path.of("synthetic"), Path.of("synthetic/.local"),
                "SCSJ_LOCAL_SYNTHETIC", "synthetic", ACCOUNT,
                new LocalFactImportMain.Credentials("synthetic", "never-print-this", 13307), List.of());

        assertThat(LocalFactVerifyMain.jdbcUrl(plan)).startsWith("jdbc:mysql://127.0.0.1:13307/SCSJ_LOCAL_SYNTHETIC?")
                .contains("allowMultiQueries=false", "readOnlyPropagatesToServer=true")
                .doesNotContain("never-print-this", ACCOUNT);
    }

    private static PreparedFactBatch input(List<String> rawRows, FactSourceType source) throws Exception {
        var array = JSON.createArrayNode();
        for (String raw : rawRows) array.add(JSON.readTree(raw));
        byte[] content = source == FactSourceType.MANUAL_HISTORY
                ? ("[" + String.join(",", rawRows) + "]").getBytes(StandardCharsets.UTF_8)
                : JSON.writeValueAsBytes(LocalFactVerifyMain.canonical(array));
        String hash = LocalFactVerifyMain.sha(content);
        List<PreparedMaterialFact> facts = rawRows.isEmpty() ? List.of()
                : List.of(new PreparedMaterialFact("synthetic-1", 1, rawRows.getFirst(),
                LocalFactVerifyMain.sha(rawRows.getFirst().getBytes(StandardCharsets.UTF_8))));
        return new PreparedFactBatch("synthetic", "sampleproject", ACCOUNT, DATE, source, "synthetic-v1", hash,
                "synthetic-schema", "synthetic-catalog", EVIDENCE, EVIDENCE, facts);
    }

    private static LocalFactVerifyMain.Version version(PreparedFactBatch input, String disposition, int count) {
        return new LocalFactVerifyMain.Version(7, ACCOUNT, DATE, "sampleproject", input.sourceType().name(),
                input.contentHash(), disposition, count, EVIDENCE, EVIDENCE);
    }

    private static LocalFactVerifyMain.Fact fact(String raw) throws Exception {
        return new LocalFactVerifyMain.Fact(7, ACCOUNT, DATE, "synthetic-1", 1, raw,
                LocalFactVerifyMain.sha(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertFailure(PreparedFactBatch input, LocalFactVerifyMain.DaySnapshot stored, String code) {
        assertThatThrownBy(() -> LocalFactVerifyMain.compare(input, stored))
                .isInstanceOf(LocalFactVerifyMain.VerificationFailure.class).hasMessage(code);
    }
}
