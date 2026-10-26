package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Compare every result field to the frozen D175 implementation, including decimal scale and reasons. */
class D180CalculationEquivalenceTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC);
    private final DashboardCalculator actual = new DashboardCalculator(CLOCK);
    private final D175ReferenceCalculator reference = new D175ReferenceCalculator(CLOCK);

    @Test void seededFactsPreserveEveryOutputIncludingMissingPairsZeroesRenamesAndCohorts() {
        for (int seed = 0; seed < 60; seed++) {
            Snapshot snapshot = fixture(seed, 35, 12);
            LocalDate date = LocalDate.of(2026, 1, 10).plusDays(seed % 14);
            int limit = 1 + seed % 12;
            assertThat(actual.top(snapshot, "demo", date, limit)).as("top seed %s", seed)
                    .isEqualTo(reference.top(snapshot, "demo", date, limit));
            DateRange current = new DateRange(date.minusDays(6), date);
            DateRange previous = seed % 3 == 0 ? new DateRange(date.minusDays(3), date.plusDays(2))
                    : new DateRange(date.minusDays(13), date.minusDays(7));
            assertThat(actual.review(snapshot, "demo", current, previous, limit)).as("review seed %s", seed)
                    .isEqualTo(reference.review(snapshot, "demo", current, previous, limit));
        }
    }

    @Test void noRequestCacheLeaksAcrossAccountVersionReplacementBackfillOrReversedPeriods() {
        for (int seed : new int[]{2, 3, 2, 10, 3}) {
            Snapshot fixture = fixture(seed, 70, 20);
            Snapshot snapshot = new Snapshot("account-" + seed, fixture.days(), fixture.facts());
            DateRange current = new DateRange(LocalDate.of(2025, 12, 27), LocalDate.of(2026, 1, 3));
            DateRange previous = new DateRange(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 1));
            assertThat(actual.review(snapshot, "demo", current, previous, 200))
                    .isEqualTo(reference.review(snapshot, "demo", current, previous, 200));
        }
    }

    @Test void emptyAndMissingDatesAndUnknownOnlyRowsRemainDistinct() {
        Snapshot empty = new Snapshot("empty", List.of(), List.of());
        Snapshot knownEmpty = new Snapshot("empty", List.of(new DayEvidence(LocalDate.of(2026, 1, 1), 1, "hash", "MCP_AUTO")), List.of());
        for (Snapshot snapshot : List.of(empty, knownEmpty)) {
            LocalDate date = LocalDate.of(2026, 1, 1);
            assertThat(actual.top(snapshot, "demo", date, 10)).isEqualTo(reference.top(snapshot, "demo", date, 10));
            DateRange range = new DateRange(date, date.plusDays(5));
            assertThat(actual.review(snapshot, "demo", range, range, 10)).isEqualTo(reference.review(snapshot, "demo", range, range, 10));
        }
    }

    @Test void duplicateDateBoundariesAndExtremeDecimalsDoNotChangeRankingOrPrecision() {
        LocalDate day = LocalDate.of(2026, 1, 1);
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            facts.add(new Fact(day, "same", i % 2 == 0 ? null : "ZZ", day.plusDays(i),
                    new BigDecimal("1E-35"), i % 2 == 0 ? null : new BigDecimal("99999999999999999.000000000000001"),
                    BigDecimal.ZERO, new BigDecimal("1E+4"), null));
        }
        facts.add(new Fact(day.plusDays(1), "same", "future", day.minusYears(2), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        Snapshot snapshot = new Snapshot("synthetic", List.of(new DayEvidence(day, 1, "a", "MCP_AUTO"),
                new DayEvidence(day.plusDays(1), 2, "b", "MANUAL_HISTORY")), facts);
        assertThat(actual.top(snapshot, "demo", day, 200)).isEqualTo(reference.top(snapshot, "demo", day, 200));
        DateRange range = new DateRange(day, day);
        assertThat(actual.review(snapshot, "demo", range, range, 200)).isEqualTo(reference.review(snapshot, "demo", range, range, 200));
    }

    static Snapshot fixture(int seed, int dayCount, int materialCount) {
        Random random = new Random(seed);
        LocalDate start = LocalDate.of(2025, 12, 20);
        List<DayEvidence> days = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        for (int d = 0; d < dayCount; d++) {
            if (random.nextInt(17) == 0) continue;
            LocalDate day = start.plusDays(d);
            days.add(new DayEvidence(day, d + 1L + seed * 10000L, "synthetic-" + seed + "-" + d,
                    d % 2 == 0 ? "MCP_AUTO" : "MANUAL_HISTORY"));
            for (int m = 0; m < materialCount; m++) {
                if (random.nextInt(7) == 0) continue;
                String id = m == 0 ? "-2" : "m" + m;
                String name = switch (random.nextInt(7)) { case 0 -> null; case 1 -> "zz-" + m; default -> "name-" + m + "-" + (d % 3); };
                LocalDate creation = random.nextInt(4) == 0 ? null : start.plusDays(m % 20 - 10 + (d % 9 == 0 ? 40 : 0));
                facts.add(new Fact(day, id, name, creation, value(random), value(random), value(random), value(random), value(random)));
            }
        }
        Collections.shuffle(facts, random);
        return new Snapshot("synthetic-" + seed, List.copyOf(days), List.copyOf(facts));
    }

    private static BigDecimal value(Random random) {
        return switch (random.nextInt(11)) {
            case 0 -> null;
            case 1 -> BigDecimal.ZERO;
            case 2 -> new BigDecimal("0.00000000000000000000000000001");
            default -> BigDecimal.valueOf(random.nextInt(1000), random.nextInt(4));
        };
    }
}
