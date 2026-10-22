package cn.datadashboard.api;

import cn.datadashboard.history.HistoryUploadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@RestController
@RequestMapping("/api/operations/history")
public final class HistoryUploadController {
    private final HistoryUploadService service;
    private cn.datadashboard.security.TrialRequestGuard trialGuard;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public HistoryUploadController(HistoryUploadService service){this.service=service;}
    public HistoryUploadController(HistoryUploadService service,cn.datadashboard.security.TrialRequestGuard guard){this(service);this.trialGuard=Objects.requireNonNull(guard);}
    private void guard(HttpServletRequest request,boolean write){
        if(trialGuard!=null){
            HistoryUploadService.check(trialGuard.permits(request,write,true),"ACCESS_DENIED");
            if(write){HistoryUploadService.check(service.writable(),"HISTORY_WRITE_DISABLED");
                HistoryUploadService.check("1".equals(request.getHeader("X-SCSJ-History-Write")),"ACCESS_DENIED");}
            return;
        }
        Set<String> hosts=Set.of("127.0.0.1:5180","127.0.0.1:18080"), origins=Set.of("http://127.0.0.1:5180","http://127.0.0.1:18080");
        String origin=request.getHeader("Origin"),host=request.getHeader("Host"),remote=request.getRemoteAddr();
        HistoryUploadService.check(host!=null&&hosts.contains(host)&&remote!=null&&Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote)
                &&(origin==null||origins.contains(origin))&&!"cross-site".equals(request.getHeader("Sec-Fetch-Site")),"LOCAL_REQUEST_REQUIRED");
        if(write){HistoryUploadService.check(service.writable(),"HISTORY_WRITE_DISABLED");
            HistoryUploadService.check(origin!=null&&origins.contains(origin)&&"1".equals(request.getHeader("X-SCSJ-History-Write")),"LOCAL_REQUEST_REQUIRED");}
    }
    @GetMapping("/template") public ResponseEntity<byte[]> template(HttpServletRequest request){guard(request,false);return ResponseEntity.ok()
            .header("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .header("Content-Disposition","attachment; filename=manual-history.xlsx").header("Cache-Control","no-store").body(service.template());}
    @GetMapping("/projects/{projectId}/context") public Object context(@PathVariable String projectId,HttpServletRequest request){guard(request,false);return service.context(projectId);}
    @PostMapping(value="/projects/{projectId}/preview",consumes="application/octet-stream")
    public Object preview(@PathVariable String projectId,HttpServletRequest request)throws java.io.IOException{guard(request,true);return service.preview(projectId,request.getInputStream(),request.getParameter("mode")==null?"HISTORY":request.getParameter("mode"));}
    @GetMapping("/projects/{projectId}/uploads/{uploadId}") public Object get(@PathVariable String projectId,@PathVariable String uploadId,HttpServletRequest request){guard(request,false);return service.get(projectId,uploadId);}
    @PostMapping(value="/projects/{projectId}/uploads/{uploadId}/apply",consumes="application/json")
    public Object apply(@PathVariable String projectId,@PathVariable String uploadId,@RequestBody String body,HttpServletRequest request){
        guard(request,true);JsonNode node=parse(body,Set.of("previewHash","confirmAccount","confirmCompleteDays"));
        HistoryUploadService.check(node.path("confirmAccount").isBoolean()&&node.path("confirmCompleteDays").isBoolean(),"REQUEST_INVALID");
        if(trialGuard!=null)return service.applyAs(projectId,uploadId,node.path("previewHash").stringValue(),node.path("confirmAccount").booleanValue(),node.path("confirmCompleteDays").booleanValue(),cn.datadashboard.security.OperationActor.authenticated(request.getUserPrincipal().getName()));
        return service.apply(projectId,uploadId,node.path("previewHash").stringValue(),node.path("confirmAccount").booleanValue(),node.path("confirmCompleteDays").booleanValue());}
    @PostMapping(value="/projects/{projectId}/uploads/{uploadId}/discard",consumes="application/json")
    public Object discard(@PathVariable String projectId,@PathVariable String uploadId,@RequestBody String body,HttpServletRequest request){guard(request,true);return service.discard(projectId,uploadId,parse(body,Set.of("previewHash")).path("previewHash").stringValue());}
    private JsonNode parse(String body,Set<String> keys){HistoryUploadService.check(body!=null&&body.length()<=4096,"REQUEST_INVALID");
        JsonNode node=json.readTree(body);HistoryUploadService.check(node!=null&&node.isObject()&&node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(keys)
                &&node.path("previewHash").isString()&&node.path("previewHash").stringValue().matches("[a-f0-9]{64}"),"REQUEST_INVALID");return node;}
    @ExceptionHandler(HistoryUploadService.Failure.class) public ResponseEntity<Map<String,String>> known(HistoryUploadService.Failure e){
        int status=Set.of("LOCAL_REQUEST_REQUIRED","HISTORY_WRITE_DISABLED","ACCESS_DENIED").contains(e.code)?403:
                Set.of("HISTORY_UNAVAILABLE","PYTHON_UNAVAILABLE","TEMPLATE_UNAVAILABLE").contains(e.code)?503:409;
        return ResponseEntity.status(status).body(Map.of("code",e.code));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> other(){return ResponseEntity.badRequest().body(Map.of("code","HISTORY_REQUEST_FAILED"));}
}
