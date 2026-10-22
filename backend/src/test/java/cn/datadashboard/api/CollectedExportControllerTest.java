package cn.datadashboard.api;

import cn.datadashboard.export.CollectedExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectedExportControllerTest {
    CollectedExportController controller(){return new CollectedExportController(new CollectedExportService(id->new CollectedExportService.Project(id,"demo","a"),
        (a,s,e)->new CollectedExportService.Source(List.of(),List.of(new CollectedExportService.Day(s.toString(),0)),List.of()),p->new byte[]{'P','K',3,4}));}
    HttpServletRequest request(){var r=mock(HttpServletRequest.class);when(r.getHeader("Host")).thenReturn("127.0.0.1:18080");when(r.getRemoteAddr()).thenReturn("127.0.0.1");return r;}
    @Test void localReadWithoutWriteHeadersAllowed(){assertNotNull(controller().preview("p","2026-01-01","2026-01-01",request()));}
    @Test void crossSiteOrRemoteRequestsRejected(){
        for(String field:List.of("Origin","Host","Sec-Fetch-Site","remote")){
            var r=request();if(field.equals("remote"))when(r.getRemoteAddr()).thenReturn("192.0.2.1");else when(r.getHeader(field)).thenReturn(field.equals("Sec-Fetch-Site")?"cross-site":"https://invalid.example");
            assertEquals("LOCAL_REQUEST_REQUIRED",assertThrows(CollectedExportService.Failure.class,()->controller().preview("p","2026-01-01","2026-01-01",r)).code);
        }
    }
    @Test void downloadUsesAttachmentNoStore(){var response=controller().download("p","2026-01-01","2026-01-01",request());assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("2026-01-01"));}
}
