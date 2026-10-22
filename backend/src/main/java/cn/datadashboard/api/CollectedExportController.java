package cn.datadashboard.api;

import cn.datadashboard.export.CollectedExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/operations/exports")
public final class CollectedExportController {
    private final CollectedExportService service;
    private cn.datadashboard.security.TrialRequestGuard trialGuard;
    public CollectedExportController(CollectedExportService service){this.service=service;}
    public CollectedExportController(CollectedExportService service,cn.datadashboard.security.TrialRequestGuard guard){this(service);this.trialGuard=Objects.requireNonNull(guard);}
    private void guard(HttpServletRequest r){
        if(trialGuard!=null){CollectedExportService.check(trialGuard.permits(r,false,false),"LOCAL_REQUEST_REQUIRED");return;}
        String origin=r.getHeader("Origin");
        CollectedExportService.check(Set.of("127.0.0.1:5180","127.0.0.1:18080").contains(Objects.toString(r.getHeader("Host"),""))
            &&Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(Objects.toString(r.getRemoteAddr(),""))
            &&(origin==null||Set.of("http://127.0.0.1:5180","http://127.0.0.1:18080").contains(origin))
            &&!"cross-site".equals(r.getHeader("Sec-Fetch-Site")),"LOCAL_REQUEST_REQUIRED");
    }
    @GetMapping("/projects/{projectId}/preview") public Object preview(@PathVariable String projectId,@RequestParam String startDate,@RequestParam String endDate,HttpServletRequest r){
        guard(r);return ResponseEntity.ok().header("Cache-Control","no-store").body(service.preview(projectId,startDate,endDate));
    }
    @GetMapping("/projects/{projectId}/download") public ResponseEntity<byte[]> download(@PathVariable String projectId,@RequestParam String startDate,@RequestParam String endDate,HttpServletRequest r){
        guard(r);byte[] bytes=service.download(projectId,startDate,endDate);
        return ResponseEntity.ok().header("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .header("Content-Disposition","attachment; filename=collected-data_"+java.time.LocalDate.parse(startDate)+"_"+java.time.LocalDate.parse(endDate)+".xlsx")
            .header("Cache-Control","no-store").body(bytes);
    }
    @ExceptionHandler(CollectedExportService.Failure.class) public ResponseEntity<Map<String,String>> known(CollectedExportService.Failure e){
        return ResponseEntity.status(e.code.equals("LOCAL_REQUEST_REQUIRED")?403:e.code.equals("EXPORT_BUILD_FAILED")||e.code.equals("EXPORT_TIMEOUT")?503:409).body(Map.of("code",e.code));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> unknown(){return ResponseEntity.badRequest().body(Map.of("code","EXPORT_REQUEST_FAILED"));}
}
