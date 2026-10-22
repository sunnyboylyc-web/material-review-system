package cn.datadashboard.api;

import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;

/** Local read-only dashboard endpoints. No arbitrary account ID or SQL is accepted. */
@RestController
@RequestMapping("/api")
public final class DashboardController {
    private final Function<String, DashboardModels.Snapshot> snapshots;
    private final Function<String, DashboardCalculator.PreparedSnapshot> prepared;
    private final ProjectDirectory directory;
    private final DashboardCalculator calculator = new DashboardCalculator();

    public DashboardController(Supplier<DashboardModels.Snapshot> snapshots) {
        this(ProjectDirectory.single("SYNTHETIC_LEGACY_TEST"), ignored -> snapshots.get());
    }
    public DashboardController(ProjectDirectory directory, Function<String, DashboardModels.Snapshot> snapshots) {
        this(directory, snapshots, null);
    }

    public DashboardController(ProjectDirectory directory, Function<String, DashboardModels.Snapshot> snapshots,
                               Function<String, DashboardCalculator.PreparedSnapshot> prepared) {
        this.directory = directory;
        this.snapshots = snapshots;
        this.prepared = prepared;
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return directory.projects();
    }

    /** Latest validated current MCP day, not the latest manual day or query selection. */
    public record ProjectFreshness(String schemaVersion, String projectId, LocalDate latestMcpStatDate) { }

    @GetMapping("/projects/{projectId}/freshness")
    public ProjectFreshness freshness(@PathVariable String projectId) {
        String account = directory.account(projectId);
        final DashboardModels.Snapshot snapshot;
        try {
            snapshot = snapshots.apply(account);
        } catch (Exception failure) {
            // A reader validation failure is unavailable evidence, never an invalid user query.
            throw new IllegalStateException("FACT_QUERY_UNAVAILABLE", failure);
        }
        if (snapshot == null || !account.equals(snapshot.accountId()) || snapshot.days() == null)
            throw new IllegalStateException("FACT_QUERY_UNAVAILABLE");
        LocalDate latest = null;
        for (var day : snapshot.days()) {
            if (day == null || day.date() == null || day.sourceType() == null)
                throw new IllegalStateException("FACT_QUERY_UNAVAILABLE");
            if ("MCP_AUTO".equals(day.sourceType()) && (latest == null || day.date().isAfter(latest)))
                latest = day.date();
        }
        return new ProjectFreshness("freshness-1.0.0", projectId, latest);
    }

    @GetMapping("/projects/{projectId}/review")
    public DashboardModels.Review review(@PathVariable String projectId,
            @RequestParam String currentStart, @RequestParam String currentEnd,
            @RequestParam String previousStart, @RequestParam String previousEnd,
            @RequestParam(defaultValue = "20") int limit) {
        String account = directory.account(projectId);
        var current = range(currentStart, currentEnd);
        var previous = range(previousStart, previousEnd);
        requireLimit(limit);
        if (prepared != null) {
            var input = prepared.apply(account);
            if (input == null || !account.equals(input.snapshot().accountId()))
                throw new IllegalStateException("FACT_QUERY_UNAVAILABLE");
            return calculator.reviewWithMaterialTypesPrepared(input, projectId, current, previous, limit);
        }
        var snapshot = snapshots.apply(account);
        if (snapshot == null || !account.equals(snapshot.accountId()))
            throw new IllegalStateException("FACT_QUERY_UNAVAILABLE");
        return calculator.reviewWithMaterialTypesPrepared(DashboardCalculator.prepare(snapshot), projectId, current, previous, limit);
    }

    @GetMapping("/projects/{projectId}/top")
    public DashboardModels.Top top(@PathVariable String projectId, @RequestParam String date,
            @RequestParam(defaultValue = "20") int limit) {
        String account = directory.account(projectId);
        LocalDate selected = date(date);
        requireLimit(limit);
        if (prepared != null) {
            var input = prepared.apply(account);
            if (input == null || !account.equals(input.snapshot().accountId()))
                throw new IllegalStateException("FACT_QUERY_UNAVAILABLE");
            return calculator.topPrepared(input, projectId, selected, limit);
        }
        return calculator.top(snapshots.apply(account), projectId, selected, limit);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("LIMIT_OUT_OF_BOUNDS");
    }
    private static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException();
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1900 || parsed.getYear() > 2200) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("DATE_INVALID");
        }
    }
    private static DashboardModels.DateRange range(String start, String end) {
        LocalDate first = date(start), last = date(end);
        if (last.isBefore(first) || java.time.temporal.ChronoUnit.DAYS.between(first, last) >= 3660)
            throw new IllegalArgumentException("RANGE_INVALID");
        return new DashboardModels.DateRange(first, last);
    }

    @ExceptionHandler(ProjectDirectory.ProjectNotEnabled.class)
    public ResponseEntity<Map<String, String>> missingProject() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "PROJECT_NOT_ENABLED"));
    }
    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_QUERY"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> failedQuery() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "FACT_QUERY_UNAVAILABLE"));
    }
}
