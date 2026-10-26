package cn.datadashboard.ingest.persistence;

import cn.datadashboard.calculation.DashboardModels;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdbcHistoryFactGatewayTest {
    @TempDir Path temp;
    static final LocalDate DAY = LocalDate.of(2026, 1, 1), FIRST = DAY.plusDays(1);

    PreparedFactBatch batch() throws Exception {
        Path manifest = temp.resolve("normalized.json"), facts = temp.resolve("facts.jsonl");
        Files.writeString(manifest, "{}"); Files.writeString(facts, "");
        return new PreparedFactBatch("synthetic-run", "synthetic-project", "synthetic-account", DAY,
                FactSourceType.MANUAL_HISTORY, "synthetic-version", sha("[]"), "1.0.0", "manual-history-upload-1.0.0",
                evidence(manifest), evidence(facts), List.of());
    }
    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static InputFileEvidence evidence(Path p) throws Exception {
        return new InputFileEvidence(p.getFileName().toString(),p.toAbsolutePath().toString(),Files.size(p),sha(Files.readString(p)));
    }
    DataSource source() throws Exception {
        DataSource ds=mock(DataSource.class); Connection c=mock(Connection.class);
        when(ds.getConnection()).thenReturn(c); when(c.getAutoCommit()).thenReturn(true);
        when(c.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED); return ds;
    }
    @Test void firstMcpUsesOnlyCurrentHeadersNeverMaterialRows() throws Exception {
        DataSource ds=source();
        try (var templates=mockConstruction(JdbcTemplate.class,(m,c)->{
            when(m.query(contains("LEFT JOIN"),any(RowMapper.class),anyString())).thenReturn(List.of(new JdbcHistoryFactGateway.CurrentDay(DAY.minusYears(1),"MANUAL_HISTORY"),new JdbcHistoryFactGateway.CurrentDay(FIRST,"MCP_AUTO")));
            when(m.query(contains("SELECT DISTINCT"),any(RowMapper.class),anyString())).thenReturn(List.of(DAY,FIRST));
        })) {
            var context=new JdbcHistoryFactGateway(ds).inspect("synthetic-account");
            assertThat(context.firstMcpDate()).isEqualTo(FIRST); assertThat(context.existingDates()).containsExactlyInAnyOrder(DAY,FIRST);
            assertThat(context.manualHistoryDates()).containsExactly(DAY.minusYears(1));
            verify(ds.getConnection()).setReadOnly(true);
            verify(templates.constructed().getFirst(),never()).query(contains("scsj_material_day_fact"),any(RowMapper.class),anyString());
        }
    }
    @Test void zeroRowManualCurrentDatesRemainExactAndExcludeMachineAndNoncurrentVersions() throws Exception {
        DataSource ds=source();LocalDate manual=DAY.minusDays(3),machine=DAY,oldOnly=DAY.minusDays(1);
        try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->{
            when(m.query(contains("LEFT JOIN"),any(RowMapper.class),anyString())).thenAnswer(invocation->{
                var result=new java.util.ArrayList<Object>();RowMapper<?> mapper=invocation.getArgument(1);
                for(var date:List.of(manual,machine)) {
                    java.sql.ResultSet row=mock(java.sql.ResultSet.class);
                    when(row.getObject("pointer_id",Long.class)).thenReturn(3L);when(row.getObject("version_id",Long.class)).thenReturn(3L);
                    when(row.getDate("state_date")).thenReturn(Date.valueOf(date));when(row.getDate("version_date")).thenReturn(Date.valueOf(date));
                    when(row.getString("state_account")).thenReturn("synthetic-account");when(row.getString("version_account")).thenReturn("synthetic-account");
                    when(row.getString("source_type")).thenReturn(date.equals(manual)?"MANUAL_HISTORY":"MCP_AUTO");when(row.getString("disposition")).thenReturn("CURRENT");
                    when(row.getString("content_hash")).thenReturn("0".repeat(64));when(row.getObject("row_count",Integer.class)).thenReturn(0);
                    result.add(mapper.mapRow(row,result.size()));
                }return result;
            });
            when(m.query(contains("SELECT DISTINCT"),any(RowMapper.class),anyString())).thenReturn(List.of(manual,oldOnly,machine));
        })) {
            var context=new JdbcHistoryFactGateway(ds).inspect("synthetic-account");
            assertThat(context.manualHistoryDates()).containsExactly(manual);
            assertThat(context.existingDates()).containsExactlyInAnyOrder(manual,oldOnly,machine);
            assertThat(context.firstMcpDate()).isEqualTo(machine);
            verify(ds.getConnection(),never()).commit();
        }
    }
    @Test void corruptSnapshotDoesNotBecomeSuccessfulMcpDay() throws Exception {
        try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->when(m.query(contains("LEFT JOIN"),any(RowMapper.class),anyString())).thenThrow(new IllegalStateException("private")))) {
            assertThatThrownBy(()->new JdbcHistoryFactGateway(source()).inspect("synthetic-account"))
                    .hasMessage("HISTORY_CONTEXT_UNAVAILABLE");
        }
    }
    @Test void brokenOrCrossAccountPointerFailsClosedWithoutReadingFacts() throws Exception {
        for (boolean missing : List.of(true,false)) {
            DataSource ds=source();
            try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->when(m.query(contains("LEFT JOIN"),any(RowMapper.class),anyString())).thenAnswer(invocation->{
                java.sql.ResultSet row=mock(java.sql.ResultSet.class);
                when(row.getObject("pointer_id",Long.class)).thenReturn(3L);
                when(row.getObject("version_id",Long.class)).thenReturn(missing?null:3L);
                when(row.getDate("state_date")).thenReturn(Date.valueOf(FIRST));
                when(row.getDate("version_date")).thenReturn(Date.valueOf(FIRST));
                when(row.getString("state_account")).thenReturn("synthetic-account");
                when(row.getString("version_account")).thenReturn("different-account");
                when(row.getString("source_type")).thenReturn("MCP_AUTO");
                when(row.getString("disposition")).thenReturn("CURRENT");
                when(row.getString("content_hash")).thenReturn("0".repeat(64));
                when(row.getObject("row_count",Integer.class)).thenReturn(1);
                RowMapper<?> mapper=invocation.getArgument(1); return List.of(mapper.mapRow(row,0));
            }))) {
                assertThatThrownBy(()->new JdbcHistoryFactGateway(ds).inspect("synthetic-account")).hasMessage("HISTORY_CONTEXT_UNAVAILABLE");
            }
        }
    }
    @Test void transactionIsActiveInsideStoreAndCommitted() throws Exception {
        DataSource ds=source(); PreparedFactBatch b=batch();
        try(var readers=mockConstruction(JdbcDashboardSnapshotReader.class,(m,c)->when(m.read(anyString())).thenReturn(
                new DashboardModels.Snapshot("synthetic-account",List.of(new DashboardModels.DayEvidence(FIRST,2,"hash","MCP_AUTO")),List.of())));
            var templates=mockConstruction(JdbcTemplate.class);
            var stores=mockConstruction(JdbcFactImportStore.class,(m,c)->when(m.importNewHistoryDay(any(),any())).thenAnswer(i->{
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return new FactStoreResult(3,FactVersionDisposition.CURRENT,FactStoreOutcome.INSERTED,0);
            }))) {
            assertThat(new JdbcHistoryFactGateway(ds).importNewDay(b).inputVersionId()).isEqualTo(3);
            verify(ds.getConnection()).commit();
        }
    }
    @Test void storeFailureRollsBackAndDoesNotLeakDetails() throws Exception {
        DataSource ds=source(); PreparedFactBatch b=batch();
        try(var readers=mockConstruction(JdbcDashboardSnapshotReader.class,(m,c)->when(m.read(anyString())).thenReturn(
                new DashboardModels.Snapshot("synthetic-account",List.of(new DashboardModels.DayEvidence(FIRST,2,"hash","MCP_AUTO")),List.of())));
            var templates=mockConstruction(JdbcTemplate.class);
            var stores=mockConstruction(JdbcFactImportStore.class,(m,c)->when(m.importNewHistoryDay(any(),any())).thenThrow(new IllegalStateException("private")))) {
            assertThatThrownBy(()->new JdbcHistoryFactGateway(ds).importNewDay(b)).hasMessage("HISTORY_IMPORT_REJECTED");
            verify(ds.getConnection(),atLeastOnce()).rollback(); verify(ds.getConnection(),never()).commit();
        }
    }
    @Test void existingAnyVersionRejectedInsideTransactionBeforeWrites() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); var b=batch();
        when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),anyString(),any(Date.class))).thenReturn(new DayVersionPointers(null,null));
        when(jdbc.queryForObject(contains("COUNT(*)"),eq(Integer.class),anyString(),any(Date.class))).thenReturn(1);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->new JdbcFactImportStore(jdbc,new VersionPlacementPolicy(),new StoredFactVersionVerifier())
                    .importNewHistoryDay(b,FIRST)).hasMessage("HISTORY_DAY_ALREADY_EXISTS");
            verify(jdbc,never()).batchUpdate(anyString(),any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
        } finally {TransactionSynchronizationManager.clear();}
    }
    @Test void noMcpDatePermitsPastDayAndPassesNullToTransactionalCheck() throws Exception {
        var b=batch(); DataSource ds=source();
        try(var readers=mockConstruction(JdbcDashboardSnapshotReader.class,(m,c)->when(m.read(anyString())).thenReturn(
                new DashboardModels.Snapshot("synthetic-account",List.of(),List.of())));
            var templates=mockConstruction(JdbcTemplate.class);
            var stores=mockConstruction(JdbcFactImportStore.class,(m,c)->when(m.importNewHistoryDay(any(),isNull())).thenReturn(new FactStoreResult(3,FactVersionDisposition.CURRENT,FactStoreOutcome.INSERTED,0)))) {
            assertThat(new JdbcHistoryFactGateway(ds).importNewDay(b).inputVersionId()).isEqualTo(3);
            verify(stores.constructed().getFirst()).importNewHistoryDay(b,null);
            verify(ds.getConnection()).commit();
        }
    }
    @Test void noMcpRejectsTodayBeforeTransaction() throws Exception {
        var original=batch(); var today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var b=new PreparedFactBatch(original.runId(),original.projectRef(),original.accountId(),today,original.sourceType(),original.dataVersion(),original.contentHash(),original.schemaVersion(),original.catalogVersion(),original.manifestEvidence(),original.materialEvidence(),original.rows());
        DataSource ds=source();
        try(var readers=mockConstruction(JdbcDashboardSnapshotReader.class,(m,c)->when(m.read(anyString())).thenReturn(new DashboardModels.Snapshot("synthetic-account",List.of(),List.of())));var templates=mockConstruction(JdbcTemplate.class)) {
            assertThatThrownBy(()->new JdbcHistoryFactGateway(ds).importNewDay(b)).hasMessage("HISTORY_MCP_CUTOFF_INVALID");
            verify(ds.getConnection(),never()).commit();
        }
    }
    @Test void changedCutoffRejectedUnderDayLock() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class); var b=batch();
        when(jdbc.queryForObject(contains("FOR UPDATE"),any(RowMapper.class),anyString(),any(Date.class))).thenReturn(new DayVersionPointers(null,null));
        when(jdbc.queryForObject(contains("COUNT(*)"),eq(Integer.class),anyString(),any(Date.class))).thenReturn(0);
        when(jdbc.queryForObject(contains("MIN(v.stat_date)"),eq(Date.class),anyString())).thenReturn(Date.valueOf(DAY));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->new JdbcFactImportStore(jdbc,new VersionPlacementPolicy(),new StoredFactVersionVerifier())
                    .importNewHistoryDay(b,FIRST)).hasMessage("HISTORY_MCP_CUTOFF_CHANGED_OR_INVALID");
            verify(jdbc,never()).batchUpdate(anyString(),any(org.springframework.jdbc.core.BatchPreparedStatementSetter.class));
        } finally {TransactionSynchronizationManager.clear();}
    }
    @Test void directStoreCallWithoutTransactionIsRejected() throws Exception {
        var b=batch();
        assertThatThrownBy(()->new JdbcFactImportStore(mock(JdbcTemplate.class),new VersionPlacementPolicy(),new StoredFactVersionVerifier())
                .importNewHistoryDay(b,FIRST)).hasMessage("HISTORY_IMPORT_CONTEXT_INVALID");
    }
    @Test void independentVerificationUsesPermanentEvidenceAndReadOnlyConnection() throws Exception {
        var b=batch(); DataSource ds=source();
        try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->{
            when(m.queryForMap(anyString(),anyLong())).thenReturn(Map.of("project_ref",b.projectRef(),"source_type","MANUAL_HISTORY",
                    "data_version",b.dataVersion(),"schema_version",b.schemaVersion(),"catalog_version",b.catalogVersion(),"current_input_version_id",3L));
            when(m.queryForObject(anyString(),any(RowMapper.class),anyLong())).thenReturn(new StoredFactVersionSnapshot(b.accountId(),DAY,
                    b.contentHash(),FactVersionDisposition.CURRENT,0,b.manifestEvidence().absolutePath(),b.manifestEvidence().sizeBytes(),b.manifestEvidence().sha256(),
                    b.materialEvidence().absolutePath(),b.materialEvidence().sizeBytes(),b.materialEvidence().sha256()));
        })) {
            new JdbcHistoryFactGateway(ds).verify(b,3);
            verify(ds.getConnection()).setReadOnly(true); verify(ds.getConnection()).rollback(); verify(ds.getConnection()).close();
        }
    }
    @Test void changedPermanentFileRejectedBeforeDatabaseRead() throws Exception {
        var b=batch(); DataSource ds=source(); Files.writeString(Path.of(b.manifestEvidence().absolutePath()),"tampered");
        assertThatThrownBy(()->new JdbcHistoryFactGateway(ds).verify(b,3)).hasMessage("HISTORY_BATCH_INVALID");
        verify(ds,never()).getConnection();
    }
    @Test void recoveryWithoutCurrentVersionDoesNotWrite() throws Exception {
        var b=batch();DataSource ds=source();
        try(var templates=mockConstruction(JdbcTemplate.class)) {
            assertThat(new JdbcHistoryFactGateway(ds).recoverCurrentDay(b).isEmpty()).isTrue();
            verify(ds.getConnection(),never()).commit();
        }
    }
    @Test void recoveryReverifiesExactCurrentEvidenceAndRejectsOtherUploadPath() throws Exception {
        var b=batch();DataSource ds=source();
        for(boolean foreign:List.of(false,true)) {
            try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->{
                when(m.query(contains("SELECT current_input_version_id"),any(RowMapper.class),eq(b.accountId()),eq(Date.valueOf(DAY)))).thenReturn(List.of(3L));
                when(m.queryForMap(anyString(),anyLong())).thenReturn(Map.of("project_ref",b.projectRef(),"source_type","MANUAL_HISTORY",
                        "data_version",b.dataVersion(),"schema_version",b.schemaVersion(),"catalog_version",b.catalogVersion(),"current_input_version_id",3L));
                when(m.queryForObject(anyString(),any(RowMapper.class),anyLong())).thenReturn(new StoredFactVersionSnapshot(b.accountId(),DAY,
                        b.contentHash(),FactVersionDisposition.CURRENT,0,foreign?b.manifestEvidence().absolutePath()+"-other":b.manifestEvidence().absolutePath(),
                        b.manifestEvidence().sizeBytes(),b.manifestEvidence().sha256(),b.materialEvidence().absolutePath(),b.materialEvidence().sizeBytes(),b.materialEvidence().sha256()));
            })) {
                var gateway=new JdbcHistoryFactGateway(ds);
                if(foreign) assertThatThrownBy(()->gateway.recoverCurrentDay(b)).hasMessage("HISTORY_READBACK_FAILED");
                else assertThat(gateway.recoverCurrentDay(b).orElseThrow()).isEqualTo(3L);
                verify(ds.getConnection(),never()).commit();
            }
        }
    }
    @Test void recoveryRejectsIdentitySourceContentAndPointerMismatches() throws Exception {
        var b=batch();DataSource ds=source();
        for(String mismatch:List.of("project","source","account","date","hash","pointer")) {
            try(var templates=mockConstruction(JdbcTemplate.class,(m,c)->{
                when(m.query(contains("SELECT current_input_version_id"),any(RowMapper.class),eq(b.accountId()),eq(Date.valueOf(DAY)))).thenReturn(List.of(3L));
                when(m.queryForMap(anyString(),anyLong())).thenReturn(Map.of("project_ref",mismatch.equals("project")?"other":b.projectRef(),
                        "source_type",mismatch.equals("source")?"MCP_AUTO":"MANUAL_HISTORY","data_version",b.dataVersion(),
                        "schema_version",b.schemaVersion(),"catalog_version",b.catalogVersion(),"current_input_version_id",mismatch.equals("pointer")?4L:3L));
                when(m.queryForObject(anyString(),any(RowMapper.class),anyLong())).thenReturn(new StoredFactVersionSnapshot(
                        mismatch.equals("account")?"other":b.accountId(),mismatch.equals("date")?DAY.plusDays(1):DAY,
                        mismatch.equals("hash")?"0".repeat(64):b.contentHash(),FactVersionDisposition.CURRENT,0,
                        b.manifestEvidence().absolutePath(),b.manifestEvidence().sizeBytes(),b.manifestEvidence().sha256(),
                        b.materialEvidence().absolutePath(),b.materialEvidence().sizeBytes(),b.materialEvidence().sha256()));
            })) {
                assertThatThrownBy(()->new JdbcHistoryFactGateway(ds).recoverCurrentDay(b)).as(mismatch).hasMessage("HISTORY_READBACK_FAILED");
                verify(ds.getConnection(),never()).commit();
            }
        }
    }
}
