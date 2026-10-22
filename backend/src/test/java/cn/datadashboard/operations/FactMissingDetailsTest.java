package cn.datadashboard.operations;

import cn.datadashboard.calculation.DashboardModels.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FactMissingDetailsTest {
    @Test void missingDetailsAreSourceObservationsNotGuessedCollectorFailures() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        Snapshot snapshot = new Snapshot("synthetic", List.of(new DayEvidence(first, 7L, "synthetic", "SYNTHETIC")), List.of(
                new Fact(first, "A", "A", null, BigDecimal.TEN, null, null, BigDecimal.ONE, null),
                new Fact(first, "zero", "Z", first, BigDecimal.ZERO, null, null, null, null)));
        var details = FactMissingDetails.inspect(snapshot, first, first.plusDays(1));
        assertThat(details).hasSize(9);
        assertThat(details.getFirst().field()).isEqualTo("createdDate");
        assertThat(details.getFirst().sourceVersionId()).isEqualTo(7L);
        assertThat(details.getFirst().reason()).isEqualTo("MISSING_CREATION_DATE");
        assertThat(details.getFirst().affectedMetrics()).isEmpty();
        assertThat(details.getLast().reason()).isEqualTo("NO_CURRENT_VERSION");
        assertThat(details.getLast().sourceVersionId()).isNull();
        assertThat(details.stream().filter(row -> "zero".equals(row.materialId())))
                .allMatch(row -> row.affectedMetrics().isEmpty());
        assertThat(details).noneMatch(row -> row.reason().contains("FAIL"));
    }

    @Test void creationConflictsAreAnchoredAtQueriedEndAndIncludeZeroDayObservations() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        Snapshot snapshot = new Snapshot("synthetic", List.of(
                new DayEvidence(first, 1L, "a", "SYNTHETIC"), new DayEvidence(first.plusDays(1), 2L, "b", "SYNTHETIC")), List.of(
                new Fact(first, "A", "A", first, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new Fact(first.plusDays(1), "A", "A", first.plusDays(1), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        assertThat(FactMissingDetails.inspect(snapshot, first, first)).isEmpty();
        assertThat(FactMissingDetails.inspect(snapshot, first, first.plusDays(1))).hasSize(2)
                .allMatch(row -> row.reason().equals("CONFLICTING_CREATION_DATE") && row.affectedMetrics().isEmpty());
    }

    @Test void individualMissingCreationHasNoOutputImpactWhenVisibleMetadataResolvesIt() {
        LocalDate first = LocalDate.of(2026, 1, 1);
        Snapshot snapshot = new Snapshot("synthetic", List.of(
                new DayEvidence(first, 1L, "a", "SYNTHETIC"), new DayEvidence(first.plusDays(1), 2L, "b", "SYNTHETIC")), List.of(
                new Fact(first, "A", "A", null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new Fact(first.plusDays(1), "A", "A", first, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        var observed = FactMissingDetails.inspect(snapshot, first, first.plusDays(1));
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst().reason()).isEqualTo("MISSING_CREATION_DATE");
        assertThat(observed.getFirst().affectedMetrics()).isEmpty();
    }

    @Test void d073AigcDoesNotReportInapplicableCreationButStillReportsUnknownMetrics() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        Snapshot snapshot = new Snapshot("synthetic", List.of(new DayEvidence(day, 1L, "a", "SYNTHETIC")),
                List.of(new Fact(day, "-2", "AIGC", null, BigDecimal.TEN, null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE)));
        var details = FactMissingDetails.inspect(snapshot, day, day);
        assertThat(details).hasSize(1);
        assertThat(details.getFirst().field()).isEqualTo("gmv");
        assertThat(details.getFirst().affectedMetrics()).containsExactly("roi");
    }
}
