package cn.datadashboard.export;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static cn.datadashboard.export.CollectedExportService.*;

class CollectedExportServiceTest {
    private final JsonMapper json=JsonMapper.builder().build();
    private CollectedExportService service(Source source){return new CollectedExportService(id->new Project(id,"demo","account-a"),(a,s,e)->source,p->new byte[]{1});}
    @Test void validRangeAndAccountResolvedServerSide(){
        AtomicReference<String> called=new AtomicReference<>();
        var service=new CollectedExportService(id->new Project(id,"demo","server-account"),(a,s,e)->{called.set(a);return new Source(List.of(),List.of(new Day(s.toString(),0)),List.of());},p->new byte[]{1});
        var result=service.preview("project","2026-01-01","2026-12-31");
        assertEquals("server-account",called.get());assertEquals("server-account",result.accountId());assertEquals("ALL_SAVED",result.sourceType());
    }
    @Test void rejectsReverseTooLongAndInvalidDate(){
        var s=service(new Source(List.of(),List.of(),List.of()));
        for(String[] range:List.of(new String[]{"2026-02-30","2026-03-01"},new String[]{"2026-03-02","2026-03-01"},new String[]{"2025-01-01","2026-01-02"}))
            assertEquals("EXPORT_RANGE_INVALID",assertThrows(Failure.class,()->s.preview("p",range[0],range[1])).code);
    }
    @Test void absentDatesRemainExplicitAndNoDataCannotDownload(){
        var s=service(new Source(List.of(),List.of(),List.of(new Excluded("2026-01-01","MISSING"),new Excluded("2026-01-02","MISSING"))));
        var p=s.preview("p","2026-01-01","2026-01-02");assertEquals(2,p.excludedDates().size());assertEquals(0,p.rowCount());
        assertEquals("EXPORT_NO_DATA",assertThrows(Failure.class,()->s.download("p","2026-01-01","2026-01-02")).code);
    }
    @Test void knownEmptyMcpDayCanBeExported(){
        var s=service(new Source(List.of(),List.of(new Day("2026-01-01",0)),List.of()));assertArrayEquals(new byte[]{1},s.download("p","2026-01-01","2026-01-01"));
    }
    @Test void rawFieldsPassWithoutUnknownOrZeroConversion(){
        var row=json.readTree("{\"material_id\":\"1234567890123456789\",\"missing\":null,\"zero\":0,\"unknown\":\"UNKNOWN\"}");
        var s=new CollectedExportService(id->new Project(id,"demo","a"),(a,from,to)->new Source(List.of(row),List.of(new Day("2026-01-01",1,"MANUAL_HISTORY")),List.of()),payload->{assertEquals(List.of(row),payload.get("rows"));assertEquals("ALL_SAVED",payload.get("sourceType"));assertEquals(List.of(new Day("2026-01-01",1,"MANUAL_HISTORY")),payload.get("days"));return new byte[]{1};});
        s.download("p","2026-01-01","2026-01-01");
    }
    @Test void overLimitNotTruncated(){
        var s=service(new Source(Collections.nCopies(100001,json.createObjectNode()),List.of(),List.of()));
        assertEquals("EXPORT_ROW_LIMIT",assertThrows(Failure.class,()->s.preview("p","2026-01-01","2026-01-01")).code);
    }
}
