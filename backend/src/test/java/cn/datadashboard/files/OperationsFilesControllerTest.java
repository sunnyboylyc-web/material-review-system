package cn.datadashboard.files;
import cn.datadashboard.api.OperationsFilesController;
import cn.datadashboard.security.TrialRequestGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class OperationsFilesControllerTest {
 @TempDir Path dir;
 @Test void ordinaryAndAnonymousDeniedAndOriginRequired() throws Exception {
   var c=new OperationsFilesController(new OperationsFileStore(dir),new TrialRequestGuard("https://example.test"),true);
   var r=new MockHttpServletRequest();
   assertThrows(SecurityException.class,()->c.list(r));
   r.setUserPrincipal(()->"viewer"); r.addUserRole("VIEWER");
   assertThrows(SecurityException.class,()->c.list(r));
   r.addUserRole("OPERATIONS"); assertNotNull(c.list(r));
   assertThrows(SecurityException.class,()->c.begin(new OperationsFilesController.NewFile("a",1),r));
   r.addHeader("Origin","https://example.test"); assertNotNull(c.begin(new OperationsFilesController.NewFile("a",1),r));
 }
 @Test void downloadDisablesProxyDiskBuffering() throws Exception {
   var store=new OperationsFileStore(dir);
   var entry=store.begin("backup.zip",3);
   store.chunk(entry.id(),0,new java.io.ByteArrayInputStream(new byte[]{1,2,3}));
   store.finish(entry.id());
   var c=new OperationsFilesController(store,new TrialRequestGuard("https://example.test"),true);
   var request=new MockHttpServletRequest(); request.setUserPrincipal(()->"ops"); request.addUserRole("OPERATIONS");
   var response=new MockHttpServletResponse();
   c.download(entry.id(),request,response);
   assertEquals("no",response.getHeader("X-Accel-Buffering"));
   assertEquals("no-store",response.getHeader("Cache-Control"));
   assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());
 }
 @Test void deletionRequiresConfirmationAndWritableOperationsContext() throws Exception {
   var store=new OperationsFileStore(dir); var e=store.begin("a",1);
   store.chunk(e.id(),0,new java.io.ByteArrayInputStream(new byte[]{1})); store.finish(e.id());
   var c=new OperationsFilesController(store,new TrialRequestGuard("https://example.test"),true);
   var request=new MockHttpServletRequest(); request.setUserPrincipal(()->"ops"); request.addUserRole("OPERATIONS");
   var yes=new OperationsFilesController.DeleteFile(true);
   assertThrows(SecurityException.class,()->c.delete(e.id(),yes,request));
   request.addHeader("Origin","https://example.test");
   assertThrows(IllegalArgumentException.class,()->c.delete(e.id(),new OperationsFilesController.DeleteFile(false),request));
   assertThrows(IllegalArgumentException.class,()->c.delete(e.id(),null,request));
   var readonly=new OperationsFilesController(store,new TrialRequestGuard("https://example.test"),false);
   assertThrows(SecurityException.class,()->readonly.delete(e.id(),yes,request));
   assertEquals(1,store.list().size()); c.delete(e.id(),yes,request); assertTrue(store.list().isEmpty());
 }
}
