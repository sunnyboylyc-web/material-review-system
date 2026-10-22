package cn.datadashboard.local;

import cn.datadashboard.ingest.persistence.FactImportStore;
import cn.datadashboard.ingest.persistence.FactSourceType;
import cn.datadashboard.ingest.persistence.PreparedFactBatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LocalFactImportMainTest {
    @TempDir Path workspace;
    private final JsonMapper json = JsonMapper.builder().build();
    private Path local;
    private Path configPath;
    private ObjectNode config;

    @BeforeEach
    void prepareOnlySyntheticConfiguration() throws Exception {
        local = Files.createDirectory(workspace.resolve(".local"));
        Path credentials = local.resolve("credentials.json");
        Files.writeString(credentials, "{\"username\":\"synthetic\",\"password\":\"synthetic-password\",\"port\":33307}");
        Path automatic = Files.createDirectory(local.resolve("automatic"));
        Files.writeString(automatic.resolve("manifest.json"), "{}");
        Files.writeString(automatic.resolve("material_day.json"), "{}");
        Path manual = local.resolve("manual-day.json");
        Files.writeString(manual, "{\"accountId\":\"12345\",\"statDate\":\"2026-09-30\",\"runId\":\"evidence-run\"}");
        config = json.createObjectNode();
        config.put("workspaceRoot", workspace.toString());
        config.put("credentialsPath", credentials.toString());
        config.put("schemaName", "SCSJ_LOCAL_SYNTHETIC");
        config.put("runId", "synthetic-run");
        config.put("expectedAccountId", "12345");
        config.putArray("autoDays").addObject().put("date", "2026-10-01")
                .put("inputDirectory", automatic.toString());
        config.putArray("manualDays").addObject().put("date", "2026-09-30")
                .put("dayEvidencePath", manual.toString());
        configPath = local.resolve("config.json");
    }

    @Test
    void dashboardCanOpenEmptyDatabaseButImportStillRequiresDays() throws Exception {
        config.putArray("autoDays");
        config.putArray("manualDays");
        json.writeValue(configPath, config);
        assertThat(LocalFactImportMain.readDashboardPlan(configPath, workspace).days()).isEmpty();
        assertThatThrownBy(this::read).hasMessage("NO_IMPORT_DAYS");
    }

    @Test
    void explicitLocalPlanPreservesAutomaticBeforeManualAndRedactsConnectionValues() throws Exception {
        var plan = read();
        assertThat(plan.days()).hasSize(2);
        assertThat(plan.days().getFirst().automatic()).isTrue();
        assertThat(plan.days().getLast().automatic()).isFalse();
        assertThat(plan.credentials().toString()).isEqualTo("Credentials[redacted]");
        assertThat(plan.toString()).isEqualTo("ImportPlan[redacted]");
        assertThat(plan.credentials().port()).isEqualTo(33307);
    }

    @Test
    void refusesSchemaOutsideDedicatedPrefixAndExtraConnectionTargets() throws Exception {
        config.put("schemaName", "mysql");
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SCHEMA_NAME_INVALID");
        config.put("schemaName", "SCSJ_LOCAL_SYNTHETIC");
        config.put("host", "remote.invalid");
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONFIG_FIELDS_MISMATCH");
    }

    @Test
    void refusesExternalSourceBeforeOpeningAnyDatabase() throws Exception {
        Path external = workspace.resolve("outside-evidence.json");
        Files.writeString(external, "{}");
        ((ObjectNode) config.get("manualDays").get(0)).put("dayEvidencePath", external.toString());
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PATH_OUTSIDE_LOCAL_ROOT");
    }

    @Test
    void refusesAccountDateMismatchAndDuplicateSourceDays() throws Exception {
        config.put("expectedAccountId", "99999");
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MANUAL_EXPECTED_IDENTITY_MISMATCH");
        config.put("expectedAccountId", "12345");
        ((ObjectNode) config.get("manualDays").get(0)).put("date", "2026-10-01");
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_OR_OVERLAPPING_DAY");
    }

    @Test
    void transactionBoundaryRejectsUnexpectedAccountAndDateBeforeDelegateWrites() {
        FactImportStore delegate = mock(FactImportStore.class);
        var guarded = new LocalFactImportMain.ScopedStore(delegate, "12345");
        guarded.expect(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> guarded.importAtomically(batch("99999", LocalDate.of(2026, 9, 30))))
                .hasMessage("TRANSACTION_SCOPE_IDENTITY_MISMATCH");
        assertThatThrownBy(() -> guarded.importAtomically(batch("12345", LocalDate.of(2026, 10, 1))))
                .hasMessage("TRANSACTION_SCOPE_IDENTITY_MISMATCH");
        verify(delegate, never()).importAtomically(any());
        PreparedFactBatch expected = batch("12345", LocalDate.of(2026, 9, 30));
        guarded.importAtomically(expected);
        verify(delegate).importAtomically(expected);
    }

    @Test
    void sameAdvertiserDifferentDouyinWritesSeparateStorageWithoutChangingSourceBytes() {
        FactImportStore delegate = mock(FactImportStore.class);
        var first = new LocalFactImportMain.ScopedStore(delegate, "12345", "20001");
        var second = new LocalFactImportMain.ScopedStore(delegate, "12345", "20002");
        var date = LocalDate.of(2026, 9, 30);
        first.expect(date); second.expect(date);
        var source = batch("12345", date);
        first.importAtomically(source); second.importAtomically(source);
        verify(delegate).importAtomically(LocalFactImportMain.storageBatch(source, "20001"));
        verify(delegate).importAtomically(LocalFactImportMain.storageBatch(source, "20002"));
        assertThat(source.accountId()).isEqualTo("12345");
        assertThat(LocalFactImportMain.storageBatch(source, "20001").rows()).isEqualTo(source.rows());
        assertThat(LocalFactImportMain.storageBatch(source, "20001").contentHash()).isEqualTo(source.contentHash());
        assertThatThrownBy(() -> first.importAtomically(batch("99999", date)))
                .hasMessage("TRANSACTION_SCOPE_IDENTITY_MISMATCH");
    }

    private LocalFactImportMain.ImportPlan read() throws Exception {
        Files.write(configPath, json.writeValueAsBytes(config));
        return LocalFactImportMain.readPlan(configPath, workspace);
    }

    private PreparedFactBatch batch(String account, LocalDate date) {
        return new PreparedFactBatch("synthetic", "synthetic", account, date, FactSourceType.MANUAL_HISTORY,
                "v1", "a".repeat(64), "synthetic-schema", "synthetic-catalog", null, null, List.of());
    }
}
