package cn.datadashboard.security;

import cn.datadashboard.api.DashboardController;
import cn.datadashboard.api.ProjectDirectory;
import cn.datadashboard.calculation.DashboardModels.Review;
import java.io.*;
import java.nio.file.*;
import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Last successful review per identity/project. Restoring never queries business facts. */
@RestController
public final class ReviewSnapshotController {
    public record Query(String currentStart, String currentEnd, String previousStart, String previousEnd, int limit) { }
    public record Saved(Review result, String savedAt) { }
    public record Refreshed(Review result, String savedAt, boolean persisted, String warning) { }
    record Envelope(int version, String owner, String project, String account, Review result, String savedAt, String checksum) { }
    private final Path root;
    private final ProjectDirectory projects;
    private final DashboardController dashboard;
    private final ReviewSnapshotStorage storage;
    private final Map<String, Long> latest = new HashMap<>();
    private long sequence;
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewSnapshotController(Path root, ProjectDirectory projects, DashboardController dashboard) {
        this(root, projects, dashboard, new ReviewSnapshotStorage());
    }
    ReviewSnapshotController(Path root, ProjectDirectory projects, DashboardController dashboard, ReviewSnapshotStorage storage) {
        this.root = root; this.projects = projects; this.dashboard = dashboard; this.storage = storage;
    }
    private String account(Principal principal, String project) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return projects.account(project); }
        catch (ProjectDirectory.ProjectNotEnabled e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    }
    private String key(Principal principal, String project) {
        return PrivateUserFiles.id(principal.getName()) + "-" + PrivateUserFiles.id(project);
    }
    @GetMapping("/api/preferences/projects/{projectId}/review-snapshot")
    public synchronized ResponseEntity<Saved> get(Principal principal, @PathVariable String projectId) {
        String account = account(principal, projectId);
        Path file = root.resolve(key(principal, projectId) + ".json");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return ResponseEntity.noContent().build();
        try {
            Envelope saved = storage.read(file, Envelope.class);
            if (saved.version() != 1 || !PrivateUserFiles.id(principal.getName()).equals(saved.owner())
                    || !projectId.equals(saved.project()) || !account.equals(saved.account())
                    || saved.result() == null || !projectId.equals(saved.result().projectId())
                    || !"dashboard-query-1.7.0".equals(saved.result().schemaVersion())
                    || saved.result().periods() == null || saved.result().periods().size() != 2
                    || saved.result().cohortMaterials() == null
                    || !checksum(saved.result()).equals(saved.checksum())) throw new IOException();
            Instant.parse(saved.savedAt());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Saved(saved.result(), saved.savedAt()));
        } catch (Exception failure) { throw new ResponseStatusException(HttpStatus.CONFLICT, "REVIEW_SNAPSHOT_UNAVAILABLE"); }
    }
    @PostMapping("/api/preferences/projects/{projectId}/review-snapshot/refresh")
    public Refreshed refresh(Principal principal, @PathVariable String projectId, @RequestBody Query query) {
        String account = account(principal, projectId), key = key(principal, projectId);
        long ticket;
        synchronized (this) { ticket = ++sequence; latest.put(key, ticket); }
        final Review result;
        try {
            if (query == null) throw new IllegalArgumentException();
            result = dashboard.review(projectId, query.currentStart(), query.currentEnd(), query.previousStart(), query.previousEnd(), query.limit());
        } catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY"); }
        catch (Exception failure) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FACT_QUERY_UNAVAILABLE"); }
        synchronized (this) {
            if (latest.get(key) != ticket)
                return new Refreshed(result, null, false, "REVIEW_SNAPSHOT_SUPERSEDED");
            if (!account.equals(account(principal, projectId)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "PROJECT_BINDING_CHANGED");
            String savedAt = Instant.now().toString();
            try {
                storage.write(root.resolve(key + ".json"), new Envelope(1, PrivateUserFiles.id(principal.getName()), projectId, account, result, savedAt, checksum(result)));
                return new Refreshed(result, savedAt, true, null);
            } catch (Exception failure) {
                return new Refreshed(result, null, false, ReviewSnapshotStorage.isSizeLimit(failure)
                        ? "REVIEW_SNAPSHOT_TOO_LARGE" : "REVIEW_SNAPSHOT_SAVE_FAILED");
            }
        }
    }
    private String checksum(Review review) throws Exception {
        // Canonical ordering survives deserialization of immutable maps without duplicating a result buffer.
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var canonical = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        try (var out = new java.security.DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            canonical.writeValue(out, review);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("code", failure.getReason() == null ? "REVIEW_SNAPSHOT_UNAVAILABLE" : failure.getReason()));
    }
}
