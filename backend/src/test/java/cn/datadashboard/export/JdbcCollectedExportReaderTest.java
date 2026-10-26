package cn.datadashboard.export;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcCollectedExportReaderTest {
    record Fixture(DataSource ds,Connection c,PreparedStatement q,ResultSet rows) { }
    Fixture setup()throws Exception{
        var ds=mock(DataSource.class);var c=mock(Connection.class);var q=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c);when(c.getAutoCommit()).thenReturn(true);when(c.prepareStatement(anyString())).thenReturn(q);when(q.executeQuery()).thenReturn(rows);
        var statement=mock(Statement.class);var readOnly=mock(ResultSet.class);when(c.createStatement()).thenReturn(statement);when(statement.executeQuery(anyString())).thenReturn(readOnly);when(readOnly.next()).thenReturn(true);when(readOnly.getInt(1)).thenReturn(1);
        return new Fixture(ds,c,q,rows);
    }
    void day(Fixture f,String source,String account)throws Exception{
        var r=f.rows();when(r.next()).thenReturn(true,false);when(r.getDate(anyString())).thenReturn(java.sql.Date.valueOf("2026-01-01"));
        when(r.getString("state_account")).thenReturn(account);when(r.getString("version_account")).thenReturn(account);
        when(r.getLong("version_id")).thenReturn(1L);when(r.getLong("pointer_id")).thenReturn(1L);when(r.getString("disposition")).thenReturn("CURRENT");when(r.getString("source_type")).thenReturn(source);
        when(r.getString("content_hash")).thenReturn("4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945");
    }
    @Test void currentEmptyMcpDayIncludedMissingDayExcludedReadOnlyTransaction()throws Exception{
        var f=setup();day(f,"MCP_AUTO","a");var result=new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-02"));
        assertEquals(List.of(new CollectedExportService.Day("2026-01-01",0)),result.days());assertEquals(List.of(new CollectedExportService.Excluded("2026-01-02","MISSING")),result.excludedDates());
        verify(f.c()).setReadOnly(true);verify(f.c()).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);verify(f.c()).rollback();verify(f.q()).setString(1,"a");verify(f.c(),never()).commit();
    }
    @Test void manualHistoryIncluded()throws Exception{
        var f=setup();day(f,"MANUAL_HISTORY","a");var result=new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"));
        assertEquals(List.of(new CollectedExportService.Day("2026-01-01",0,"MANUAL_HISTORY")),result.days());assertTrue(result.excludedDates().isEmpty());
    }
    @Test void mixedCurrentSourcesIncludedOnlyMissingExcluded()throws Exception{
        var f=setup();day(f,"MANUAL_HISTORY","a");var r=f.rows();
        when(r.next()).thenReturn(true,true,false);
        when(r.getDate("state_date")).thenReturn(java.sql.Date.valueOf("2026-01-01"),java.sql.Date.valueOf("2026-01-02"));
        // The reader checks version_date twice per row.
        when(r.getDate("version_date")).thenReturn(java.sql.Date.valueOf("2026-01-01"),java.sql.Date.valueOf("2026-01-01"),java.sql.Date.valueOf("2026-01-02"),java.sql.Date.valueOf("2026-01-02"));
        when(r.getString("source_type")).thenReturn("MANUAL_HISTORY","MCP_AUTO");
        var result=new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-03"));
        assertEquals(List.of(new CollectedExportService.Day("2026-01-01",0,"MANUAL_HISTORY"),new CollectedExportService.Day("2026-01-02",0,"MCP_AUTO")),result.days());
        assertEquals(List.of(new CollectedExportService.Excluded("2026-01-03","MISSING")),result.excludedDates());
    }
    @Test void manualRawHashAndDecimalPrecisionPreserved()throws Exception{
        var f=setup();day(f,"MANUAL_HISTORY","a");
        String raw="{\"stat_time_day\":\"2026-01-01\",\"material_id\":\"-\",\"stat_cost\":0.1234567890123456789,\"missing\":null,\"zero\":0,\"unknown\":\"UNKNOWN\"}";
        when(f.rows().getObject("fact_id")).thenReturn(1L);when(f.rows().getString("fact_account")).thenReturn("a");when(f.rows().getLong("fact_version")).thenReturn(1L);
        when(f.rows().getInt("source_row_number")).thenReturn(1);when(f.rows().getInt("row_count")).thenReturn(1);when(f.rows().getString("material_id")).thenReturn("-");
        when(f.rows().getString("raw_fact_json")).thenReturn(raw);when(f.rows().getString("row_sha256")).thenReturn(hash(raw));when(f.rows().getString("content_hash")).thenReturn(hash("["+raw+"]"));
        var result=new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"));
        var row=result.rows().getFirst();
        assertEquals(new java.math.BigDecimal("0.1234567890123456789"),row.path("stat_cost").decimalValue());
        assertTrue(row.path("missing").isNull());assertEquals(0,row.path("zero").asInt());assertEquals("UNKNOWN",row.path("unknown").asString());
        assertTrue(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(row).contains("0.1234567890123456789"));
        when(f.rows().next()).thenReturn(true,false);when(f.rows().getString("content_hash")).thenReturn("bad");
        assertEquals("EXPORT_HASH_MISMATCH",assertThrows(CollectedExportService.Failure.class,()->new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"))).code);
    }
    @Test void wrongAccountRejectedNotLeaked()throws Exception{
        for(String source:List.of("MCP_AUTO","MANUAL_HISTORY")){
            var f=setup();day(f,source,"other");assertEquals("EXPORT_IDENTITY_INVALID",assertThrows(CollectedExportService.Failure.class,()->new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"))).code);
        }
    }
    @Test void corruptHashRejected()throws Exception{
        var f=setup();day(f,"MCP_AUTO","a");when(f.rows().getString("content_hash")).thenReturn("bad");assertEquals("EXPORT_HASH_MISMATCH",assertThrows(CollectedExportService.Failure.class,()->new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"))).code);
    }
    @Test void rawRowIdentityHashesAndCountVerified()throws Exception{
        var f=setup();day(f,"MCP_AUTO","a");String raw="{\"material_id\":\"1\",\"stat_time_day\":\"2026-01-01\"}";
        when(f.rows().getObject("fact_id")).thenReturn(1L);when(f.rows().getString("fact_account")).thenReturn("a");when(f.rows().getLong("fact_version")).thenReturn(1L);
        when(f.rows().getInt("source_row_number")).thenReturn(1);when(f.rows().getInt("row_count")).thenReturn(1);when(f.rows().getString("material_id")).thenReturn("1");
        when(f.rows().getString("raw_fact_json")).thenReturn(raw);when(f.rows().getString("row_sha256")).thenReturn(hash(raw));when(f.rows().getString("content_hash")).thenReturn(hash("["+raw+"]"));
        var result=new JdbcCollectedExportReader(f.ds()).read("a",LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-01"));
        assertEquals(1,result.rows().size());assertEquals("1",result.rows().getFirst().path("material_id").asString());assertEquals(1,result.days().getFirst().rowCount());
    }
    static String hash(String s)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
}
