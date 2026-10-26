package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent review cases: paired missing populations, inclusive bounds and as-of dimensions. */
class D180ReviewRegressionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final DashboardCalculator actual = new DashboardCalculator(CLOCK);
    private final D175ReferenceCalculator old = new D175ReferenceCalculator(CLOCK);

    @Test void everyMissingFieldMaskPreservesPairedRatiosAndZeroSpendExclusion() {
        LocalDate day = LocalDate.of(2024, 1, 2);
        for (int mask = 0; mask < 32; mask++) {
            for (String spend : List.of("0.00", "1E-30", "100.000")) {
                BigDecimal[] values = {new BigDecimal(spend), new BigDecimal("301.2300"),
                        new BigDecimal("0.000"), new BigDecimal("3.100"), new BigDecimal("2E+1")};
                for (int i = 0; i < 5; i++) if ((mask & (1 << i)) != 0) values[i] = null;
                List<Fact> facts = List.of(
                        fact(day.minusDays(1), "a", "ZZ", day.minusDays(1), "10.00", null),
                        new Fact(day, "a", null, day.minusDays(1), values[0], values[1], values[2], values[3], values[4]),
                        fact(day, "b", "normal", day, "10.00", "20.0"),
                        fact(day, "unknown", null, null, null, "999.00"));
                Snapshot snapshot = snapshot(facts, List.of(day.minusDays(1), day));
                check(snapshot, day, new DateRange(day.minusDays(1), day), new DateRange(day, day));
            }
        }
    }

    @Test void leapDayAndEmptyIntervalsKeepInclusiveBoundariesAndLatestVisibleName() {
        LocalDate first = LocalDate.of(2024, 2, 28), leap = first.plusDays(1), after = leap.plusDays(1);
        List<Fact> facts = new ArrayList<>(List.of(
                fact(first, "a", "old", first, "1.00", "2.00"),
                fact(leap, "a", "boundary-first", first, "2.00", null),
                fact(leap, "a", "boundary-last", first, "3.00", "6.00"),
                fact(leap, "a", null, first, "4.00", "8.00"),
                fact(after, "a", "future", first.minusYears(1), "5.00", "10.00"),
                fact(leap, "-2", "AIGC", null, "9.00", "18.00"),
                fact(leap, "z", null, leap, "9.00", "18.00")));
        Snapshot snapshot = snapshot(facts, List.of(first, leap, after, after.plusDays(2)));
        for (LocalDate anchor : List.of(first.minusDays(1), first, leap, after, after.plusDays(1), after.plusDays(2))) {
            check(snapshot, anchor, new DateRange(anchor, anchor), new DateRange(first, after));
        }
        Top atLeap = actual.top(snapshot, "review", leap, 200);
        assertThat(atLeap.rows().stream().filter(row -> row.materialId().equals("a")).findFirst().orElseThrow().materialName())
                .isEqualTo("boundary-last");
    }

    @Test void evidenceOrderAndNewVersionsDoNotChangeOrReuseFingerprintAcrossRequests() {
        LocalDate day = LocalDate.of(2024, 1, 2);
        List<Fact> facts = List.of(fact(day, "a", null, day, "2.00", "8.000"));
        Snapshot first = new Snapshot("account", List.of(new DayEvidence(day, 1, "hash-1", "MCP_AUTO")), facts);
        Snapshot second = new Snapshot("account", List.of(new DayEvidence(day, 2, "hash-2", "MANUAL_HISTORY")), facts);
        DateRange range = new DateRange(day, day);
        for (Snapshot snapshot : List.of(first, second, first)) check(snapshot, day, range, range);
        assertThat(actual.top(first, "review", day, 200).snapshotFingerprint())
                .isNotEqualTo(actual.top(second, "review", day, 200).snapshotFingerprint());
    }

    private void check(Snapshot snapshot, LocalDate day, DateRange current, DateRange previous) {
        assertThat(actual.top(snapshot, "review", day, 200)).isEqualTo(old.top(snapshot, "review", day, 200));
        assertThat(actual.review(snapshot, "review", current, previous, 200))
                .isEqualTo(old.review(snapshot, "review", current, previous, 200));
    }

    private static Snapshot snapshot(List<Fact> facts, List<LocalDate> dates) {
        List<DayEvidence> days = new ArrayList<>();
        for (LocalDate date : dates) days.add(new DayEvidence(date, days.size() + 1, "hash-" + date, "MCP_AUTO"));
        Collections.reverse(days);
        return new Snapshot("review-account", List.copyOf(days), List.copyOf(facts));
    }

    private static Fact fact(LocalDate day, String id, String name, LocalDate created, String spend, String gmv) {
        return new Fact(day, id, name, created, spend == null ? null : new BigDecimal(spend),
                gmv == null ? null : new BigDecimal(gmv), new BigDecimal("11.00"), new BigDecimal("2.0"), BigDecimal.ONE);
    }
}
