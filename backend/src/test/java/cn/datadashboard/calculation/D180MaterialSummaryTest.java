package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static org.assertj.core.api.Assertions.*;

class D180MaterialSummaryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final DashboardCalculator calculator = new DashboardCalculator(CLOCK);
    private final D175ReferenceCalculator reference = new D175ReferenceCalculator(CLOCK);

    @Test void completeMonthsPartialEdgesCrossYearsAndHistoricalAnchorsMatchEveryReferenceField() {
        for (int seed = 0; seed < 12; seed++) {
            Snapshot source = D180CalculationEquivalenceTest.fixture(seed, 1100, 16);
            var prepared = DashboardCalculator.prepare(source);
            assertThat(prepared.stats().monthlySummaries()).isLessThan(source.facts().size());
            for (int offset : new int[]{1099, 760, 400, 365, 80}) {
                LocalDate date = LocalDate.of(2025, 12, 20).plusDays(offset);
                assertThat(calculator.topPrepared(prepared, "summary", date, 20))
                        .as("monthly top seed=%s offset=%s", seed, offset)
                        .isEqualTo(reference.top(source, "summary", date, 20));
                DateRange current = new DateRange(date.minusDays(40), date);
                DateRange previous = new DateRange(date.minusDays(95), date.minusDays(41));
                assertThat(calculator.reviewPrepared(prepared, "summary", current, previous, 20))
                        .isEqualTo(reference.review(source, "summary", current, previous, 20));
            }
        }
    }

    @Test void dimensionTimelineKeepsLateCreationEvidenceConflictRenameAndUnknownFirstDaySemantics() {
        LocalDate start = LocalDate.of(2024, 2, 27);
        List<Fact> facts = List.of(
                fact(start, "a", null, null, "0.00", "0.00"),
                fact(start.plusDays(1), "a", "before", null, "1.000", null),
                fact(start.plusDays(2), "a", null, start, "2.00", "6.0000"),
                fact(start.plusDays(3), "a", "zz-later", start, "3E-20", "9E+5"),
                fact(start.plusDays(4), "a", "new", start.minusDays(1), "4.0", "12.00"),
                fact(start, "b", "b", start, "1.0", "3.00"),
                fact(start, "b", null, start, null, "9000.00"),
                fact(start.plusDays(4), "b", "b", start, "5.00", "15.000"),
                fact(start.plusDays(2), "-2", "AIGC", start.minusYears(10), "9.00", "18.000"));
        Snapshot source = snapshot(start, 8, facts);
        var prepared = DashboardCalculator.prepare(source);
        for (int day = -1; day < 9; day++) {
            LocalDate anchor = start.plusDays(day);
            assertThat(calculator.topPrepared(prepared, "timeline", anchor, 200))
                    .isEqualTo(reference.top(source, "timeline", anchor, 200));
            DateRange range = new DateRange(anchor.minusDays(3), anchor);
            assertThat(calculator.reviewPrepared(prepared, "timeline", range, range, 200))
                    .isEqualTo(reference.review(source, "timeline", range, range, 200));
        }
    }

    @Test void preparedSnapshotOwnsListsAndNeverUsesFingerprintAsADataCacheKey() {
        LocalDate day = LocalDate.of(2024, 3, 31);
        List<DayEvidence> days = new ArrayList<>(List.of(new DayEvidence(day, 1, "unchanged-evidence", "MCP_AUTO")));
        List<Fact> rows = new ArrayList<>(List.of(fact(day, "a", "first", day, "1.00", "3.000")));
        Snapshot input = new Snapshot("account", days, rows);
        var before = DashboardCalculator.prepare(input);
        Top expected = reference.top(before.snapshot(), "copy", day, 10);
        rows.set(0, fact(day, "a", "replacement", day.minusDays(20), "900.00", "1800.00"));
        var after = DashboardCalculator.prepare(input);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
        assertThat(calculator.topPrepared(before, "copy", day, 10)).isEqualTo(expected);
        assertThat(calculator.topPrepared(after, "copy", day, 10))
                .isEqualTo(reference.top(input, "copy", day, 10)).isNotEqualTo(expected);
        rows.clear();
        days.clear();
        assertThat(calculator.topPrepared(before, "copy", day, 10)).isEqualTo(expected);
        assertThatThrownBy(() -> before.snapshot().facts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> before.snapshot().days().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void onePreparedSnapshotSupportsConcurrentQueriesWithoutSharingRequestCaches() throws Exception {
        Snapshot source = D180CalculationEquivalenceTest.fixture(23, 500, 14);
        var prepared = DashboardCalculator.prepare(source);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            LocalDate date = LocalDate.of(2026, 2, 10).plusDays(i * 13L);
            Top expectedTop = reference.top(source, "concurrent", date, 10);
            DateRange current = new DateRange(date.minusDays(6), date);
            DateRange previous = new DateRange(date.minusDays(13), date.minusDays(7));
            Review expectedReview = reference.review(source, "concurrent", current, previous, 10);
            tasks.add(() -> {
                assertThat(calculator.topPrepared(prepared, "concurrent", date, 10)).isEqualTo(expectedTop);
                assertThat(calculator.reviewPrepared(prepared, "concurrent", current, previous, 10)).isEqualTo(expectedReview);
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Future<Void> task : pool.invokeAll(tasks)) task.get();
        }
    }

    @Test void backfillAndAccountReplacementRequireNewExplicitPreparation() {
        LocalDate day = LocalDate.of(2024, 3, 31);
        Snapshot original = snapshot(day, 1, List.of(fact(day, "a", "current", day, "10.00", "30.000")));
        List<Fact> backfilled = List.of(fact(day.minusDays(60), "a", "old", day.minusDays(65), "1E-25", "999999999999999999.0000000001"),
                original.facts().getFirst());
        Snapshot replacement = snapshot(day.minusDays(60), 61, backfilled);
        Snapshot otherAccount = new Snapshot("other-account", replacement.days(), replacement.facts());
        for (Snapshot input : List.of(original, replacement, otherAccount, original)) {
            var prepared = DashboardCalculator.prepare(input);
            assertThat(calculator.topPrepared(prepared, "backfill", day, 10))
                    .isEqualTo(reference.top(input, "backfill", day, 10));
        }
    }

    private static Snapshot snapshot(LocalDate start, int dayCount, List<Fact> facts) {
        List<DayEvidence> days = new ArrayList<>();
        for (int d = 0; d < dayCount; d++) days.add(new DayEvidence(start.plusDays(d), d + 1, "h-" + d, "MANUAL_HISTORY"));
        return new Snapshot("timeline-account", days, facts);
    }

    private static Fact fact(LocalDate day, String id, String name, LocalDate created, String spend, String gmv) {
        return new Fact(day, id, name, created, spend == null ? null : new BigDecimal(spend),
                gmv == null ? null : new BigDecimal(gmv), new BigDecimal("13.000"), new BigDecimal("2.00"), BigDecimal.ONE);
    }
}
