package cn.datadashboard.api;

import cn.datadashboard.operations.OperationsStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** Maintenance diagnosis only. No collection, import, mutation or arbitrary account endpoint. */
@RestController
@RequestMapping("/api/operations")
public final class OperationsController {
    @FunctionalInterface public interface StatusReader {
        OperationsStatus read(String accountId, LocalDate start, LocalDate end);
    }
    private final StatusReader reader;
    private final ProjectDirectory directory;

    public OperationsController(BiFunction<LocalDate, LocalDate, OperationsStatus> reader) {
        this(ProjectDirectory.single("SYNTHETIC_LEGACY_TEST"), (account, start, end) -> reader.apply(start, end));
    }
    public OperationsController(ProjectDirectory directory, StatusReader reader) {
        this.directory = directory;
        this.reader = Objects.requireNonNull(reader);
    }

    @GetMapping("/projects/{projectId}/days")
    public OperationsStatus days(@PathVariable String projectId,
            @RequestParam String start, @RequestParam String end) {
        String account = directory.account(projectId);
        LocalDate first = parseDate(start), last = parseDate(end);
        if (last.isBefore(first) || ChronoUnit.DAYS.between(first, last) >= 366)
            throw new IllegalArgumentException("RANGE_INVALID");
        var result = Objects.requireNonNull(reader.read(account, first, last));
        return new OperationsStatus(result.schemaVersion(), projectId, result.requestedAt(), result.range(),
                result.validationScope(), result.sourceFilesRechecked(), result.collectionStatus(),
                result.observedRange(), result.summary(), result.days(), result.missingDetails(),
                result.missingDetailTotal(), result.missingDetailsTruncated());
    }

    private static LocalDate parseDate(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException();
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() < 1900 || result.getYear() > 2200) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("DATE_INVALID");
        }
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
                .body(Map.of("code", "OPERATIONS_QUERY_UNAVAILABLE"));
    }

}
