package cn.datadashboard.api;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.history.HistoryUploadService;
import cn.datadashboard.ingest.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryUploadControllerTest {
    @TempDir Path workspace;
    HistoryUploadController controller(boolean writable)throws Exception{
        Files.createDirectories(workspace.resolve(".local"));
        var store=new ProjectConfigurationStore(workspace,workspace.resolve(".local/config"));
        store.initialize(List.of(new ProjectConfigurationStore.Project("synthetic","Synthetic","12345678",List.of("87654321"),true)));
        HistoryFactGateway gateway=new HistoryFactGateway(){public Context inspect(String id){return new Context(null,Set.of());}
            public FactStoreResult importNewDay(PreparedFactBatch batch){throw new AssertionError();} public void verify(PreparedFactBatch batch,long id){throw new AssertionError();}};
        return new HistoryUploadController(new HistoryUploadService(workspace,store,gateway,Path.of("python"),writable));
    }
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setRemoteAddr("127.0.0.1");r.addHeader("Host","127.0.0.1:5180");r.addHeader("Origin","http://127.0.0.1:5180");r.addHeader("X-SCSJ-History-Write","1");return r;}
    @Test void readonlyDeniesWritesWithoutCreatingFiles()throws Exception{var c=controller(false);assertNotNull(c.context("synthetic",request()));var error=assertThrows(HistoryUploadService.Failure.class,()->c.preview("synthetic",request()));assertEquals("HISTORY_WRITE_DISABLED",error.code);assertFalse(Files.exists(workspace.resolve(".local/history-uploads")));}
    @Test void invalidOriginsHostsAndHeadersRejected()throws Exception{var c=controller(true);for(String header:List.of("Host","Origin","X-SCSJ-History-Write","Sec-Fetch-Site")){var r=request();r.removeHeader(header);r.addHeader(header,header.equals("Sec-Fetch-Site")?"cross-site":"untrusted");var error=assertThrows(HistoryUploadService.Failure.class,()->c.preview("synthetic",r));assertEquals("LOCAL_REQUEST_REQUIRED",error.code);}var r=request();r.setRemoteAddr("10.0.0.1");assertThrows(HistoryUploadService.Failure.class,()->c.context("synthetic",r));}
    @Test void malformedConfirmationRejected()throws Exception{var c=controller(true);assertThrows(Exception.class,()->c.apply("synthetic","0".repeat(32),"{\"previewHash\":\""+"0".repeat(64)+"\",\"confirmAccount\":\"true\",\"confirmCompleteDays\":true}",request()));assertFalse(Files.exists(workspace.resolve(".local/history-uploads")));}
}
