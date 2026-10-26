package cn.datadashboard.operations;

import java.time.LocalDate;
import java.util.List;

/** Safe operations-status-v1 projection. No account IDs, source paths or material payloads. */
public record OperationsStatus(
        String schemaVersion,
        String projectId,
        String requestedAt,
        DateRange range,
        String validationScope,
        boolean sourceFilesRechecked,
        String collectionStatus,
        ObservedRange observedRange,
        Summary summary,
        List<Day> days,
        List<MissingDetail> missingDetails,
        int missingDetailTotal,
        boolean missingDetailsTruncated
) {
    public OperationsStatus(String schemaVersion, String projectId, String requestedAt, DateRange range,
            String validationScope, boolean sourceFilesRechecked, String collectionStatus,
            ObservedRange observedRange, Summary summary, List<Day> days) {
        this(schemaVersion, projectId, requestedAt, range, validationScope, sourceFilesRechecked,
                collectionStatus, observedRange, summary, days, List.of(), 0, false);
    }
    public record MissingDetail(LocalDate date, String materialId, String field, String reason,
                                List<String> affectedMetrics, Long sourceVersionId) { }
    public record DateRange(LocalDate start, LocalDate end) { }
    /** Stored CURRENT evidence boundaries, not the beginning/end of a collection obligation. */
    public record ObservedRange(LocalDate first, LocalDate last) { }
    public record Summary(int queriedDays, int availableDays, int emptyDays, int missingDays, int isolatedDays) { }
    public record Day(LocalDate date, String state, Version currentVersion,
                      Long previousVersionId, int isolatedVersionCount) { }
    public record Version(long versionId, String sourceType, String contentHash,
                          int rowCount, String updatedAt) { }
}
