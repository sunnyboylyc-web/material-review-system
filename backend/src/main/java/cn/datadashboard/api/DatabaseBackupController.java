package cn.datadashboard.api;

import cn.datadashboard.backup.WeeklyDatabaseBackupStore;
import cn.datadashboard.backup.PeriodBackupService;
import cn.datadashboard.security.TrialRequestGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/** Explicitly registered only by the authenticated trial runner, never by local anonymous mode. */
@RestController
@RequestMapping("/api/operations/database-backup")
public final class DatabaseBackupController {
    private final WeeklyDatabaseBackupStore store;
    private final TrialRequestGuard guard;
    private WeeklyDatabaseBackupStore custom;
    private PeriodBackupService jobs;
    public DatabaseBackupController(WeeklyDatabaseBackupStore store, TrialRequestGuard guard) {
        this.store = Objects.requireNonNull(store); this.guard = Objects.requireNonNull(guard);
    }
    public DatabaseBackupController withCustom(WeeklyDatabaseBackupStore custom, PeriodBackupService jobs) {
        this.custom = Objects.requireNonNull(custom); this.jobs = Objects.requireNonNull(jobs); return this;
    }
    public record Range(String startDate, String endDate) { }
    public record CustomStatus(WeeklyDatabaseBackupStore.Status backup, PeriodBackupService.Job job) { }
    @GetMapping("/custom") public CustomStatus customStatus(HttpServletRequest request, HttpServletResponse response) throws Exception {
        check(request); response.setHeader("Cache-Control", "no-store");
        if (custom == null) throw new IllegalArgumentException();
        return new CustomStatus(custom.status(), jobs.status());
    }
    @PostMapping("/custom") public ResponseEntity<?> create(@RequestBody Range range, HttpServletRequest request) {
        if (!guard.permits(request, true, true)) throw new SecurityException();
        if (jobs == null || range == null) throw new IllegalArgumentException();
        return ResponseEntity.accepted().body(jobs.start(range.startDate(), range.endDate()));
    }
    @GetMapping("/custom/download") public void customDownload(@RequestParam String generation, HttpServletRequest request, HttpServletResponse response) throws Exception {
        check(request);
        if (custom == null) throw new IllegalArgumentException();
        send(custom, generation, response);
    }
    private void check(HttpServletRequest request) {
        if (!guard.permits(request, false, true)) throw new SecurityException();
    }
    @GetMapping public WeeklyDatabaseBackupStore.Status status(HttpServletRequest request, HttpServletResponse response) throws Exception {
        check(request); response.setHeader("Cache-Control", "no-store"); return store.status();
    }
    @GetMapping("/download") public void download(@RequestParam String generation, HttpServletRequest request,
                                                HttpServletResponse response) throws Exception {
        check(request);
        send(store, generation, response);
    }
    private void send(WeeklyDatabaseBackupStore source, String generation, HttpServletResponse response) throws Exception {
        try (var download = source.openDownload(generation)) {
            response.setContentType("application/octet-stream");
            response.setContentLengthLong(download.latest().bytes());
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(download.filename()).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Accel-Buffering", "no");
            download.writeTo(response.getOutputStream());
        }
    }
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> denied() {
        return ResponseEntity.status(403).body(Map.of("error", "ACCESS_DENIED"));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_BACKUP_GENERATION"));
    }
    @ExceptionHandler(WeeklyDatabaseBackupStore.Conflict.class) public ResponseEntity<?> conflict(WeeklyDatabaseBackupStore.Conflict failure) {
        return ResponseEntity.status(409).body(Map.of("error", failure.getMessage()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "DATABASE_BACKUP_UNAVAILABLE"));
    }
}
