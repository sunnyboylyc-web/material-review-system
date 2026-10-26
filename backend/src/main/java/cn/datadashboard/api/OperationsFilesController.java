package cn.datadashboard.api;

import cn.datadashboard.files.OperationsFileStore;
import cn.datadashboard.security.TrialRequestGuard;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/operations/files")
public final class OperationsFilesController {
    private final OperationsFileStore store;
    private final TrialRequestGuard guard;
    private final boolean writable;
    public OperationsFilesController(OperationsFileStore store, TrialRequestGuard guard, boolean writable) {
        this.store=store; this.guard=Objects.requireNonNull(guard); this.writable=writable;
    }
    private void check(HttpServletRequest r, boolean write) {
        if (!guard.permits(r,write,true) || (write && !writable)) throw new SecurityException();
    }
    public record NewFile(String name,long bytes) {}
    public record DeleteFile(boolean confirmed) {}
    @DeleteMapping("/{id}") public Object delete(@PathVariable String id,@RequestBody DeleteFile confirmation,HttpServletRequest r) throws Exception {
        check(r,true);
        if (confirmation == null || !confirmation.confirmed()) throw new IllegalArgumentException("CONFIRMATION_REQUIRED");
        store.deleteSaved(id); return Map.of("deleted",true);
    }
    @GetMapping public Object list(HttpServletRequest r) throws Exception { check(r,false); return Map.of("files",store.list(),"writeEnabled",writable,"chunkBytes",OperationsFileStore.CHUNK_BYTES); }
    @PostMapping public Object begin(@RequestBody NewFile file,HttpServletRequest r) throws Exception { check(r,true); return store.begin(file.name(),file.bytes()); }
    @PutMapping(value="/{id}/chunks",consumes="application/octet-stream")
    public Object chunk(@PathVariable String id,@RequestParam long offset,HttpServletRequest r) throws Exception { check(r,true); return store.chunk(id,offset,r.getInputStream()); }
    @PostMapping("/{id}/complete") public Object finish(@PathVariable String id,HttpServletRequest r) throws Exception { check(r,true); return store.startFinish(id); }
    @DeleteMapping("/{id}/pending") public Object cancel(@PathVariable String id,HttpServletRequest r) throws Exception { check(r,true); store.cancel(id); return Map.of("cancelled",true); }
    @GetMapping("/{id}/download")
    public void download(@PathVariable String id,HttpServletRequest r,HttpServletResponse response) throws Exception {
        check(r,false); var e=store.ready(id);
        response.setContentType("application/octet-stream"); response.setContentLengthLong(e.bytes());
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(e.name(),StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Accel-Buffering","no");
        try(var in=store.open(id)) { in.transferTo(response.getOutputStream()); }
    }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("error","ACCESS_DENIED")); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("error","INVALID_FILE_OR_CHUNK")); }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException ex) { return ResponseEntity.status(409).body(Map.of("error",ex.getMessage())); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failed() { return ResponseEntity.status(503).body(Map.of("error","FILE_STORAGE_UNAVAILABLE")); }
}
