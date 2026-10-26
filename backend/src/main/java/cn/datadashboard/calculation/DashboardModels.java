package cn.datadashboard.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static cn.datadashboard.calculation.MetricArithmetic.Result;

/** dashboard-query-v1. Null fact fields are unknown, never implicit zero. */
public final class DashboardModels {
    private DashboardModels() { }
    public record Fact(LocalDate date, String materialId, String materialName, LocalDate createdDate,
                       BigDecimal spend, BigDecimal gmv, BigDecimal impressions, BigDecimal clicks,
                       BigDecimal orders) { }
    public record DayEvidence(LocalDate date, long versionId, String contentHash, String sourceType) { }
    public record Snapshot(String accountId, List<DayEvidence> days, List<Fact> facts) { }
    public record DateRange(LocalDate start, LocalDate end) { }
    public record Coverage(String state, long expectedDays, long coveredDays, List<LocalDate> missingDates) { }
    public record FirstSpend(String status, LocalDate date, LocalDate earliestObservedDate) { }
    public record CreationDate(String status, LocalDate date, String reason) { }
    public record Group(String key, Map<String, Result> metrics) { }
    public record Period(String key, String label, DateRange range, Coverage coverage,
                         List<Group> groups, int pendingMaterialCount) { }
    public record MaterialRow(int rank, String materialId, String materialName, Result currentSpend,
                              Result previousSpend, Result periodChange, FirstSpend firstSpend,
                              Result spendShare, Result roi, CreationDate creationDate) { }
    /** Complete current-period cohort; previousSpend always refers to the same material ID. */
    public record ReviewMaterialRow(int rank, String materialId, String materialName, Result currentSpend,
                                    Result previousSpend, Result periodChange, FirstSpend firstSpend,
                                    Result spendShare, Result roi, Result ctr, Result cvr, Result cpm,
                                    List<DailySpend> dailySpend, CreationDate creationDate) { }
    /** On-demand complete list; never added to saved review envelopes. */
    public record ReviewMaterials(String schemaVersion, String projectId, String evidenceKind,
                                  DateRange current, DateRange previous, String snapshotFingerprint,
                                  List<ReviewMaterialRow> rows) { }
    public record Review(String schemaVersion, String projectId, String evidenceKind, String requestedAt,
                         int topComparisonDisplayLimit, List<Period> periods, String metricsState,
                         List<Group> comparisons, List<MaterialRow> newMaterials,
                         List<ReviewMaterialRow> topMaterials, String snapshotFingerprint,
                         Map<String, List<ReviewMaterialRow>> cohortMaterials,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         MaterialTypeReview materialTypes) {
        /** Legacy calculations/saved results remain distinguishable from a computed empty type result. */
        public Review(String schemaVersion, String projectId, String evidenceKind, String requestedAt,
                      int topComparisonDisplayLimit, List<Period> periods, String metricsState,
                      List<Group> comparisons, List<MaterialRow> newMaterials,
                      List<ReviewMaterialRow> topMaterials, String snapshotFingerprint,
                      Map<String, List<ReviewMaterialRow>> cohortMaterials) {
            this(schemaVersion, projectId, evidenceKind, requestedAt, topComparisonDisplayLimit, periods,
                    metricsState, comparisons, newMaterials, topMaterials, snapshotFingerprint, cohortMaterials, null);
        }
    }
    public record MaterialTypePeriod(String key, DateRange range, Coverage coverage, List<Group> groups,
                                     int excludedUndatedMaterials, int unresolvedDateMaterials,
                                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                     DateRange testRange) {
        public MaterialTypePeriod(String key, DateRange range, Coverage coverage, List<Group> groups,
                                  int excludedUndatedMaterials, int unresolvedDateMaterials) {
            this(key, range, coverage, groups, excludedUndatedMaterials, unresolvedDateMaterials, null);
        }
    }
    public record MaterialTypeAnalysis(String schemaVersion, String projectId, String evidenceKind,
                                       String requestedAt, String snapshotFingerprint, MaterialTypeReview analysis) { }
    public record MaterialTypeRow(int rank, String materialId, String materialName, CreationDate creationDate,
                                  Map<String, Result> metrics, Result previousSpend, List<DailySpend> dailySpend) { }
    public record MaterialTypeReview(String schemaVersion, List<MaterialTypePeriod> periods,
                                     List<Group> comparisons, Map<String, List<MaterialTypeRow>> materials,
                                     @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                     AiMaterialReview ai) {
        public MaterialTypeReview(String schemaVersion, List<MaterialTypePeriod> periods,
                                  List<Group> comparisons, Map<String, List<MaterialTypeRow>> materials) {
            this(schemaVersion, periods, comparisons, materials, null);
        }
    }
    public record AiMaterialReview(List<MaterialTypePeriod> periods, List<Group> comparisons,
                                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                   Map<String, List<MaterialTypeRow>> materials) {
        public AiMaterialReview(List<MaterialTypePeriod> periods, List<Group> comparisons) {
            this(periods, comparisons, null);
        }
    }
    public record TopDay(LocalDate date, String availability) { }
    public record DailySpend(LocalDate date, Result spend) { }
    public record TopRow(int rank, String materialId, String materialName, List<DailySpend> dailySpend,
                         TopThreeDayTrend.Result trend, Result annualRoi, Result windowRoi, Result dayRoi,
                         FirstSpend firstSpend, Result recent3Orders, Result recent3Spend,
                         Result recent3Cpc, Result recent3Cpm, Result recent3Roi,
                         Result lifetimeSpend, Result lifetimeRoi, Coverage lifetimeCoverage,
                         CreationDate creationDate, Result windowSpend,
                         Result recent3Impressions, Result recent3Clicks,
                         Result recent3Ctr, Result recent3Cvr) { }
    public record Top(String schemaVersion, String projectId, String evidenceKind, String requestedAt,
                      LocalDate selectedDate, int displayLimit, Coverage coverage, List<TopDay> days,
                      String rankingState, Coverage recent3Coverage, Coverage annualCoverage,
                      List<TopRow> rows, String snapshotFingerprint) { }
}
