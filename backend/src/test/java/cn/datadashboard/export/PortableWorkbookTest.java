package cn.datadashboard.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PortableWorkbookTest {
    @TempDir Path isolated;

    @Test void actualPythonProcessBuildsWorkbookAndCleansTemporaryFiles() throws Exception {
        String python=System.getenv("SCSJ_TEST_PYTHON");
        assumeTrue(python!=null && Files.isRegularFile(Path.of(python)), "Explicit test Python required");
        isolated=isolated.toRealPath();
        Path root=Path.of("..").toRealPath();
        for(String name:List.of("tools/manual_history/export_saved_data.py",
                "contracts/manual-history-upload-v1.json", "backend/src/main/resources/templates/manual-history.xlsx")) {
            Path target=isolated.resolve(name);Files.createDirectories(target.getParent());
            Files.copy(root.resolve(name),target);
        }
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectName","synthetic");payload.put("accountId","1000000000000001");
        payload.put("startDate","2026-10-12");payload.put("endDate","2026-10-12");
        payload.put("days",List.of(Map.of("statDate","2026-10-12","rowCount",1,"sourceType","MANUAL_HISTORY")));
        payload.put("excludedDates",List.of());
        payload.put("rows",List.of(Map.of("stat_time_day","2026-10-12","material_id","9999999999999900081",
                "stat_cost_for_roi2","0","roi2_material_video_name","=literal")));
        var builder=CollectedExportService.pythonWorkbook(isolated,python);
        byte[] workbook=builder.build(payload);
        boolean found=false;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if(entry.getName().equals("xl/worksheets/sheet2.xml")) {
                    String xml=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(xml.contains("9999999999999900081"));assertTrue(xml.contains("=literal"));
                    assertFalse(xml.contains("<x:f>"));found=true;
                }
            }
        }
        assertTrue(found);
        try(var files=Files.list(isolated.resolve(".local/collected-exports"))) {assertEquals(0,files.count());}
        payload.put("rows",List.of());
        assertEquals("EXPORT_BUILD_FAILED",assertThrows(CollectedExportService.Failure.class,()->builder.build(payload)).code);
        try(var files=Files.list(isolated.resolve(".local/collected-exports"))) {assertEquals(0,files.count());}
    }
}
