package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static cn.datadashboard.calculation.DashboardModels.*;
import static cn.datadashboard.calculation.MetricArithmetic.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardCalculatorTest {
    private final DashboardCalculator calculator = new DashboardCalculator(
            Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void reviewComputesAllGroupsAndExcludesZzOnlyFromDistinctCount() {
        Snapshot snapshot = complete(1, 4, List.of(
                fact(1, "A", "normal", 1, "10", "20", "100", "10", "1"),
                fact(2, "-2", "AIGC aggregate", null, "7", "14", "70", "7", "1"),
                fact(3, "A", "normal", 1, "20", "80", "1000", "20", "2"),
                fact(3, "B", "new normal", 3, "30", "60", "300", "30", "3"),
                fact(3, "-2", "AIGC aggregate", null, "7", "14", "70", "7", "1"),
                fact(4, "ZZ-ID", "sample_zz", 4, "5", "10", "50", "5", "1"),
                fact(3, "zero-only", "normal", 3, "0", "999", "999999", "9999", null)));

        Review review = calculator.review(snapshot, "demo", range(3, 4), range(1, 2), 20);
        Period current = review.periods().getFirst();

        assertThat(review.periods()).extracting(Period::key).containsExactly("CURRENT", "PREVIOUS");
        assertMetric(current, "overall", "spend", "62");
        assertMetric(current, "overall", "materialCount", "3");
        assertMetric(current, "overall", "ctr", divide("62", "1420"));
        assertMetric(current, "overall", "cvr", divide("7", "62"));
        assertMetric(current, "overall", "roi", divide("164", "62"));
        assertMetric(current, "overall", "cpm", divide("62000", "1420"));
        assertMetric(current, "overall", "spendShare", "1");
        assertMetric(current, "new", "spend", "35");
        assertMetric(current, "new", "materialCount", "1");
        assertMetric(current, "historical", "spend", "20");
        assertMetric(current, "historical", "materialCount", "1");
        assertMetric(current, "aigc", "spend", "7");
        assertMetric(current, "aigc", "materialCount", "1");
        assertThat(current.pendingMaterialCount()).isZero();
        assertThat(review.periods().get(1).pendingMaterialCount()).isZero();
        assertThat(review.metricsState()).isEqualTo("READY");
        assertThat(review.topMaterials()).extracting(ReviewMaterialRow::materialId).containsExactly("B", "A", "-2", "ZZ-ID");
        assertThat(review.newMaterials()).extracting(MaterialRow::materialId).containsExactly("B", "ZZ-ID");
        assertThat(review.topMaterials().get(2).firstSpend().status()).isEqualTo("PENDING");
        assertThat(review.comparisons().getFirst().metrics().get("spend").value())
                .isEqualByComparingTo(divide("62", "17"));
    }

    @Test
    void eachPeriodClassifiesIndependentlyAndComparisonDiffersFromMaterialGrowth() {
        Snapshot snapshot = complete(1, 3, List.of(
                simple(1, "A", "A", 1, "100"), simple(3, "A", "A", 1, "120")));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(1, 1), 20);

        assertMetric(review.periods().getFirst(), "historical", "spend", "120");
        assertMetric(review.periods().get(1), "new", "spend", "100");
        assertThat(review.comparisons().getFirst().metrics().get("spend").value()).isEqualByComparingTo("1.2");
        assertThat(review.topMaterials().getFirst().periodChange().value()).isEqualByComparingTo("0.2");
        assertThat(review.metricsState()).isEqualTo("READY");
        // The two user-selected periods need not be chronological or adjacent.
        Review reversed = calculator.review(snapshot, "demo", range(1, 1), range(3, 3), 20);
        assertMetric(reversed.periods().getFirst(), "new", "spend", "100");
    }

    @Test
    void topSeparatesSevenDayThreeDayAndYearlyWindows() {
        Snapshot snapshot = complete(1, 8, List.of(
                fact(1, "A", "name", 1, "100", "1000", "100", "10", "1"),
                fact(2, "A", "name", 1, "10", "20", "100", "10", "1"),
                fact(4, "A", "name", 1, "20", "40", "100", "10", "1"),
                fact(5, "A", "name", 1, "30", "60", "100", "10", "1"),
                fact(6, "A", "name", 1, "100", "300", "100", "10", "1"),
                fact(7, "A", "name", 1, "200", "800", "200", "20", "2"),
                fact(8, "A", "name", 1, "800", "1600", "800", "80", "8")));
        Top top = calculator.top(snapshot, "demo", day(8), 20);
        TopRow row = top.rows().getFirst();

        assertThat(top.rankingState()).isEqualTo("READY");
        assertThat(top.coverage().state()).isEqualTo("COMPLETE");
        assertThat(top.annualCoverage().expectedDays()).isEqualTo(8);
        assertThat(row.dailySpend()).extracting(daily -> daily.spend().value())
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(decimal("800"), decimal("200"), decimal("100"), decimal("30"),
                        decimal("20"), BigDecimal.ZERO, decimal("10"));
        assertThat(row.recent3Spend().value()).isEqualByComparingTo("1100");
        assertThat(row.recent3Roi().value()).isEqualByComparingTo(divide("2700", "1100"));
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("1260");
        assertThat(row.lifetimeRoi().value()).isEqualByComparingTo(divide("3820", "1260"));
        assertThat(row.lifetimeCoverage().state()).isEqualTo("COMPLETE");
        assertThat(row.recent3Orders().value()).isEqualByComparingTo("11");
        assertThat(row.recent3Cpc().value()).isEqualByComparingTo("10");
        assertThat(row.recent3Cpm().value()).isEqualByComparingTo("1000");
        assertThat(row.windowRoi().value()).isEqualByComparingTo(divide("2820", "1160"));
        assertThat(row.annualRoi().value()).isEqualByComparingTo(divide("3820", "1260"));
        assertThat(row.dayRoi().value()).isEqualByComparingTo("2");
        assertThat(row.trend().label()).isEqualTo("明显上升");
        assertThat(row.trend().lastGap()).isEqualByComparingTo("600");
        assertThat(row.firstSpend()).isEqualTo(new FirstSpend("VERIFIED", day(1), day(1)));
        assertThat(top.days()).extracting(TopDay::availability).containsOnly("AVAILABLE");
    }

    @Test
    void yearlyRoiAndMetadataNeverIncludeEarlierYearOrFutureFacts() {
        LocalDate oldDay = LocalDate.of(2025, 12, 31);
        List<Fact> facts = List.of(
                new Fact(oldDay, "A", "old name", oldDay, decimal("1000"), decimal("100000"),
                        decimal("100"), decimal("10"), decimal("1")),
                simple(2, "A", "selected name", null, "10"),
                simple(3, "A", "future name", 3, "100000"));
        Snapshot snapshot = complete(oldDay, day(3), facts);
        Top top = calculator.top(snapshot, "demo", day(2), 20);

        assertThat(top.rows().getFirst().annualRoi().value()).isEqualByComparingTo("2");
        assertThat(top.rows().getFirst().lifetimeSpend().value()).isEqualByComparingTo("1010");
        assertThat(top.rows().getFirst().lifetimeRoi().value()).isEqualByComparingTo(divide("100020", "1010"));
        assertThat(top.rows().getFirst().lifetimeCoverage().expectedDays()).isEqualTo(3);
        assertThat(top.rows().getFirst().lifetimeCoverage().state()).isEqualTo("COMPLETE");
        assertThat(top.rows().getFirst().materialName()).isEqualTo("selected name");
        assertThat(top.rows().getFirst().firstSpend().status()).isEqualTo("VERIFIED");
        assertThat(top.rows().getFirst().firstSpend().date()).isEqualTo(oldDay);
        assertThat(top.coverage().state()).isEqualTo("PARTIAL");
        assertThat(top.annualCoverage().state()).isEqualTo("COMPLETE");
    }

    @Test
    void missingDayIsUnknownForDailyTrendButKnownDaysStillContributePartialSums() {
        Snapshot snapshot = removeDay(complete(1, 8, List.of(
                simple(6, "A", "A", 6, "100"), simple(8, "A", "A", 6, "800"))), day(7));
        Top top = calculator.top(snapshot, "demo", day(8), 20);
        TopRow row = top.rows().getFirst();

        assertThat(top.coverage().state()).isEqualTo("PARTIAL");
        assertThat(top.coverage().missingDates()).containsExactly(day(7));
        assertThat(top.recent3Coverage().coveredDays()).isEqualTo(2);
        assertThat(row.dailySpend().get(1).spend().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(row.dailySpend().get(3).spend().value()).isEqualByComparingTo("0");
        assertThat(row.recent3Spend().value()).isEqualByComparingTo("900");
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("900");
        assertThat(row.lifetimeRoi().value()).isEqualByComparingTo("2");
        assertThat(row.lifetimeCoverage().missingDates()).containsExactly(day(7));
        assertThat(row.lifetimeCoverage().state()).isEqualTo("PARTIAL");
        assertThat(row.trend().status()).isEqualTo(TopThreeDayTrend.Status.UNKNOWN_INPUT);
        Review review = calculator.review(snapshot, "demo", range(6, 8), range(1, 2), 20);
        assertMetric(review.periods().getFirst(), "overall", "spend", "900");
        assertThat(review.metricsState()).isEqualTo("PARTIAL");
    }

    @Test
    void noCoverageIsNotTheSameAsCompleteEmptyDay() {
        Snapshot snapshot = complete(1, 1, List.of());
        Review review = calculator.review(snapshot, "demo", range(2, 2), range(1, 1), 20);

        assertThat(metric(review.periods().getFirst(), "overall", "spend").status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(metric(review.periods().getFirst(), "overall", "materialCount").status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertMetric(review.periods().get(1), "overall", "spend", "0");
        assertMetric(review.periods().get(1), "overall", "materialCount", "0");
        assertThat(metric(review.periods().get(1), "overall", "roi").status()).isEqualTo(Status.ZERO_DENOMINATOR);
        assertThat(calculator.top(snapshot, "demo", day(2), 20).rankingState()).isEqualTo("UNAVAILABLE");
        assertThat(calculator.top(snapshot, "demo", day(1), 20).rankingState()).isEqualTo("READY");
        assertThat(calculator.top(snapshot, "demo", day(1), 20).rows()).isEmpty();
        assertThat(calculator.review(snapshot, "demo", range(2, 2), range(3, 3), 20).metricsState())
                .isEqualTo("UNAVAILABLE");
    }

    @Test
    void requiredFieldUnknownOnlyAffectsDependentMetricsAndZeroRowsAreIgnored() {
        Snapshot snapshot = complete(1, 3, List.of(
                fact(3, "A", "A", 3, "10", "20", null, "5", "1"),
                fact(3, "zero", null, 3, "0", null, null, null, null)));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(1, 2), 20);
        Period current = review.periods().getFirst();

        assertMetric(current, "overall", "spend", "10");
        assertMetric(current, "overall", "roi", "2");
        assertMetric(current, "overall", "cvr", "0.2");
        assertMetric(current, "overall", "materialCount", "1");
        assertThat(metric(current, "overall", "ctr").status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(metric(current, "overall", "cpm").status()).isEqualTo(Status.UNKNOWN_INPUT);
        TopRow row = calculator.top(snapshot, "demo", day(3), 20).rows().getFirst();
        assertThat(row.recent3Cpc().value()).isEqualByComparingTo("2");
        assertThat(row.recent3Cpm().status()).isEqualTo(Status.UNKNOWN_INPUT);
    }

    @Test
    void unknownSpendKeepsKnownRankingButMarksItPartial() {
        Snapshot snapshot = complete(1, 3, List.of(
                simple(3, "A", "A", 3, "10"), simple(3, "B", "B", 3, null)));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(1, 2), 20);

        assertThat(metric(review.periods().getFirst(), "overall", "spend").value()).isEqualByComparingTo("10");
        assertThat(metric(review.periods().getFirst(), "overall", "spend").partialReasons()).contains("UNKNOWN_SPEND");
        assertThat(metric(review.periods().getFirst(), "new", "spend").partial()).isTrue();
        assertThat(review.topMaterials()).extracting(ReviewMaterialRow::materialId).containsExactly("A");
        assertThat(review.newMaterials()).hasSize(1);
        Top top = calculator.top(snapshot, "demo", day(3), 20);
        assertThat(top.rankingState()).isEqualTo("PARTIAL");
        assertThat(top.rows()).hasSize(1);
    }

    @Test
    void creationUsesEarliestSourceOrBusinessObservationWithoutFirstSpendProof() {
        Snapshot snapshot = complete(2, 4, List.of(
                simple(2, "old", "old", null, "5"),
                simple(3, "old", "old", null, "5"),
                simple(3, "missing-history", "missing-history", 1, "10"),
                simple(3, "creation-after-spend", "creation-after-spend", 4, "10"),
                simple(3, "proven-new", "proven-new", 2, "10")));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(2, 2), 20);
        Period current = review.periods().getFirst();

        assertMetric(current, "historical", "spend", "25");
        assertMetric(current, "new", "spend", "10");
        assertMetric(current, "overall", "spend", "35");
        assertThat(current.pendingMaterialCount()).isZero();
        assertThat(review.newMaterials()).extracting(MaterialRow::materialId).containsExactly("creation-after-spend");
        ReviewMaterialRow old = review.topMaterials().stream().filter(row -> row.materialId().equals("old")).findFirst().orElseThrow();
        assertThat(old.firstSpend()).isEqualTo(new FirstSpend("PENDING", null, day(2)));
    }

    @Test
    void futureCreationMetadataCannotTurnAnEarlierCandidateIntoVerifiedNew() {
        Snapshot snapshot = complete(1, 4, List.of(
                simple(2, "A", null, null, "10"), simple(4, "A", "future", 1, "20")));
        Review review = calculator.review(snapshot, "demo", range(2, 2), range(1, 1), 20);

        assertThat(review.periods().getFirst().pendingMaterialCount()).isZero();
        assertThat(review.topMaterials().getFirst().materialName()).isNull();
        assertThat(review.topMaterials().getFirst().firstSpend().status()).isEqualTo("PENDING");
        assertThat(review.newMaterials()).hasSize(1);
        TopRow row = calculator.top(snapshot, "demo", day(2), 20).rows().getFirst();
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("10");
        assertThat(row.lifetimeSpend().partialReasons()).doesNotContain("UNKNOWN_CREATION_DATE");
        assertThat(row.creationDate()).isEqualTo(new CreationDate("VERIFIED", day(2), null));
        assertThat(row.lifetimeRoi().value()).isEqualByComparingTo("2");
        assertThat(row.lifetimeCoverage().expectedDays()).isEqualTo(1);
    }

    @Test
    void lifetimeUnknownRevenueIsNotZeroAndZeroDenominatorsRemainExplicit() {
        Snapshot snapshot = complete(1, 3, List.of(
                fact(1, "A", "A", 1, "10", null, "10", "0", "0"),
                fact(2, "A", "A", 1, "0", "9999", "9999", "9999", "9999"),
                fact(3, "A", "A", 1, "20", "0", "0", "0", "0")));
        TopRow row = calculator.top(snapshot, "demo", day(3), 20).rows().getFirst();
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("30");
        assertThat(row.lifetimeCoverage().state()).isEqualTo("COMPLETE");
        assertThat(row.lifetimeRoi().value()).isEqualByComparingTo("0");
        assertThat(row.lifetimeRoi().partialReasons()).contains("MISSING_FIELD");
        assertThat(row.recent3Roi().value()).isEqualByComparingTo("0");
        assertThat(row.dayRoi().value()).isEqualByComparingTo("0");
        assertThat(row.recent3Cpc().status()).isEqualTo(Status.ZERO_DENOMINATOR);
        // TOP eligibility requires positive selected-day spend: lifetime ROI cannot have a
        // zero denominator on an emitted row. Empty/zero-spend days must emit no fake row.
        assertThat(calculator.top(complete(1, 1, List.of(simple(1, "Z", "Z", 1, "0"))),
                "demo", day(1), 20).rows()).isEmpty();
    }

    @Test
    void inconsistentCreationOrUnknownPriorSpendPreventsFirstSpendProof() {
        Snapshot inconsistent = complete(1, 3, List.of(
                simple(1, "A", "A", 1, "0"), simple(3, "A", "A", 2, "10")));
        assertThat(calculator.top(inconsistent, "demo", day(3), 20).rows().getFirst().firstSpend().status())
                .isEqualTo("PENDING");
        Snapshot unknown = complete(1, 3, List.of(
                simple(1, "A", "A", 1, null), simple(3, "A", "A", 1, "10")));
        assertThat(calculator.top(unknown, "demo", day(3), 20).rows().getFirst().firstSpend().status())
                .isEqualTo("PENDING");
    }

    @Test
    void tiesUseTextIdAndKeepAigcZzInRankingsWithStrictLimits() {
        Snapshot snapshot = complete(1, 1, List.of(
                simple(1, "2", "normal", 1, "10"),
                simple(1, "10", "ZZ", 1, "10"),
                simple(1, "-2", "AIGC aggregate", null, "10")));
        Review review = calculator.review(snapshot, "demo", range(1, 1), range(1, 1), 2);

        assertThat(review.topMaterials()).extracting(ReviewMaterialRow::materialId).containsExactly("-2", "10");
        assertThat(review.topMaterials()).extracting(ReviewMaterialRow::rank).containsExactly(1, 2);
        assertThat(calculator.top(snapshot, "demo", day(1), 2).rows()).extracting(TopRow::materialId)
                .containsExactly("-2", "10");
        assertMetric(review.periods().getFirst(), "overall", "materialCount", "2");
    }

    @Test
    void newMaterialListHasItsOwnFixedTenLimit() {
        List<Fact> facts = new ArrayList<>();
        for (int value = 1; value <= 12; value++) {
            facts.add(simple(1, "id-" + value, "normal", 1, Integer.toString(value)));
        }
        Review review = calculator.review(complete(1, 1, facts), "demo", range(1, 1), range(1, 1), 2);
        assertThat(review.topMaterials()).hasSize(2);
        assertThat(review.newMaterials()).hasSize(10);
        assertThat(review.newMaterials().getFirst().materialId()).isEqualTo("id-12");
    }

    @Test
    void unknownNameIsNotAutomaticallyNonZzAndCountsRemainRowBased() {
        Snapshot unknown = complete(1, 2, List.of(simple(2, "A", null, 2, "10")));
        Review pendingCount = calculator.review(unknown, "demo", range(1, 2), range(1, 1), 20);
        assertThat(metric(pendingCount.periods().getFirst(), "overall", "materialCount").status())
                .isEqualTo(Status.UNKNOWN_INPUT);
        assertMetric(pendingCount.periods().getFirst(), "overall", "spend", "10");
        assertThat(pendingCount.topMaterials()).hasSize(1);
        Snapshot known = complete(1, 3, List.of(simple(1, "A", "normal", 1, "5"),
                simple(2, "A", null, 1, "10"), simple(3, "A", "ZZ", 1, "20")));
        assertThat(calculator.top(known, "demo", day(2), 20).rows().getFirst().materialName()).isEqualTo("normal");
        assertMetric(calculator.review(known, "demo", range(1, 3), range(1, 1), 20).periods().getFirst(),
                "overall", "materialCount", "1");
        assertMetric(calculator.review(known, "demo", range(3, 3), range(1, 1), 20).periods().getFirst(),
                "overall", "materialCount", "0");
    }

    @Test
    void fingerprintIsOrderIndependentButBindsEvenOutOfRangeLoadedVersions() {
        Snapshot original = complete(1, 3, List.of(simple(2, "A", "A", 2, "10")));
        List<DayEvidence> reversed = new ArrayList<>(original.days());
        Collections.reverse(reversed);
        String hash = calculator.top(original, "demo", day(2), 20).snapshotFingerprint();
        assertThat(calculator.top(new Snapshot(original.accountId(), reversed, original.facts()), "demo", day(2), 20)
                .snapshotFingerprint()).isEqualTo(hash).matches("[0-9a-f]{64}");
        List<DayEvidence> changed = new ArrayList<>(original.days());
        changed.set(2, new DayEvidence(day(3), 100, "changed-hash", "AUTO"));
        assertThat(calculator.top(new Snapshot(original.accountId(), changed, original.facts()), "demo", day(2), 20)
                .snapshotFingerprint()).isNotEqualTo(hash);
    }

    @Test
    void rejectsInvalidRangeLimitAndUnanchoredFactsRatherThanSilentlyChangingRequest() {
        Snapshot snapshot = complete(1, 1, List.of());
        assertThatThrownBy(() -> calculator.review(snapshot, "demo", range(2, 1), range(1, 1), 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.review(snapshot, "demo", new DateRange(day(1), day(1).plusDays(3660)), range(1, 1), 20))
                .isInstanceOf(IllegalArgumentException.class);
        for (int invalid : List.of(0, -1, 201)) {
            assertThatThrownBy(() -> calculator.top(snapshot, "demo", day(1), invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> calculator.top(snapshot, "demo", LocalDate.MIN, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.top(new Snapshot("synthetic", List.of(),
                List.of(simple(1, "A", "A", 1, "10"))), "demo", day(1), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cohortDetailsAreCompleteIndependentlyRankedAndCompareSameMaterialNotPreviousCohort() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 1; i <= 220; i++) {
            facts.add(simple(1, "old-" + i, "normal", 1, "1"));
            facts.add(simple(3, "old-" + i, "normal", 1, Integer.toString(1000 + i)));
        }
        for (int i = 1; i <= 12; i++) facts.add(simple(3, "new-" + i, "normal", 3, Integer.toString(i)));
        Review review = calculator.review(complete(1, 3, facts), "demo", range(3, 3), range(1, 1), 1);
        assertThat(review.schemaVersion()).isEqualTo("dashboard-query-1.7.0");
        assertThat(review.topMaterials()).hasSize(1);
        assertThat(review.cohortMaterials().get("historical")).hasSize(220);
        assertThat(review.cohortMaterials().get("new")).hasSize(12);
        assertThat(review.cohortMaterials().get("new").subList(0, 10))
                .extracting(ReviewMaterialRow::materialId)
                .containsExactly("new-12", "new-11", "new-10", "new-9", "new-8", "new-7", "new-6", "new-5", "new-4", "new-3");
        assertThat(review.cohortMaterials().get("historical").getFirst().previousSpend().value()).isEqualByComparingTo("1");
        assertThat(review.cohortMaterials().get("new").getFirst().previousSpend().value()).isEqualByComparingTo("0");
        assertThat(review.cohortMaterials().get("new").getLast().rank()).isEqualTo(12);
    }

    @Test
    void cohortCurveCoversArbitraryCrossYearWindowWithMissingZeroAndPositiveDays() {
        LocalDate start = LocalDate.of(2025, 12, 29);
        List<Fact> facts = List.of(
                new Fact(start, "A", "A", start, decimal("10"), decimal("20"), decimal("100"), decimal("10"), decimal("1")),
                fact(1, "A", "A", null, "0", "999999", "999999", "999999", "999999"),
                fact(3, "A", "A", null, "20", "60", "200", "40", "8"));
        Snapshot snapshot = removeDay(complete(start, day(5), facts), LocalDate.of(2025, 12, 31));
        Review review = calculator.review(snapshot, "demo", new DateRange(start, day(5)), range(3, 3), 20);
        ReviewMaterialRow row = review.cohortMaterials().get("new").getFirst();
        assertThat(row.dailySpend()).hasSize(8);
        assertThat(row.dailySpend()).extracting(DailySpend::date).containsExactly(
                start, start.plusDays(1), start.plusDays(2), day(1), day(2), day(3), day(4), day(5));
        assertThat(row.dailySpend().get(2).spend().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(row.dailySpend().get(1).spend().value()).isEqualByComparingTo("0");
        assertThat(row.dailySpend().get(3).spend().value()).isEqualByComparingTo("0");
        assertThat(row.currentSpend().value()).isEqualByComparingTo("30");
        assertThat(row.previousSpend().value()).isEqualByComparingTo("20");
        assertThat(row.roi().value()).isEqualByComparingTo(divide("80", "30"));
        assertThat(row.ctr().value()).isEqualByComparingTo(divide("50", "300"));
        assertThat(row.cvr().value()).isEqualByComparingTo(divide("9", "50"));
        assertThat(row.cpm().value()).isEqualByComparingTo("100");
        assertThat(review.periods().getFirst().coverage().missingDates()).containsExactly(start.plusDays(2));
    }

    @Test
    void cohortDetailsPreservePendingAigcZzAndUnknownMetricSemantics() {
        Snapshot snapshot = complete(1, 3, List.of(
                simple(1, "-2", "AIGC aggregate", null, "1"),
                simple(3, "-2", "AIGC aggregate", null, "10"),
                fact(3, "zz", "zz material", 3, "10", null, null, "0", "0"),
                simple(3, "pending", "pending", null, "30"),
                simple(3, "new", "new", 3, "10")));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(1, 1), 20);
        assertThat(review.cohortMaterials().get("historical")).isEmpty();
        assertThat(review.topMaterials()).extracting(ReviewMaterialRow::materialId).contains("-2");
        assertThat(review.cohortMaterials().get("new")).extracting(ReviewMaterialRow::materialId).containsExactly("pending", "new", "zz");
        ReviewMaterialRow zz = review.cohortMaterials().get("new").get(2);
        assertThat(zz.roi().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(zz.ctr().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(zz.cpm().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(zz.cvr().status()).isEqualTo(Status.ZERO_DENOMINATOR);
        assertMetric(review.periods().getFirst(), "overall", "materialCount", "3");
        assertMetric(review.periods().getFirst(), "new", "materialCount", "2");
        assertMetric(review.periods().getFirst(), "historical", "materialCount", "0");
        assertMetric(review.periods().getFirst(), "aigc", "materialCount", "1");
        assertThat(review.periods().getFirst().pendingMaterialCount()).isZero();
    }

    @Test
    void cohortDetailsShowKnownPartialRankingButNoRowsWhenCoverageMissing() {
        Snapshot snapshot = complete(1, 3, List.of(simple(3, "A", "A", 3, "10"), simple(3, "B", "B", 3, null)));
        Review review = calculator.review(snapshot, "demo", range(3, 3), range(1, 1), 20);
        assertThat(review.cohortMaterials().get("new")).extracting(ReviewMaterialRow::materialId).containsExactly("A");
        assertThat(review.metricsState()).isEqualTo("PARTIAL");
        assertThat(calculator.review(snapshot, "demo", range(4, 4), range(1, 1), 20)
                .cohortMaterials().values()).allMatch(List::isEmpty);
    }

    @Test
    void d065UsesSourceCreationEvenWhenFirstPositiveIsLaterAndHistoryHasGaps() {
        Snapshot snapshot = removeDay(complete(1, 5, List.of(
                simple(1, "A", "A", 1, "0"), simple(4, "A", "A", 1, "10"))), day(2));
        TopRow row = calculator.top(snapshot, "demo", day(4), 20).rows().getFirst();
        assertThat(row.creationDate()).isEqualTo(new CreationDate("VERIFIED", day(1), null));
        assertThat(row.firstSpend().status()).isEqualTo("PENDING");
        assertThat(row.lifetimeCoverage().expectedDays()).isEqualTo(4);
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("10");
        assertThat(row.lifetimeSpend().partialReasons()).contains("MISSING_ACCOUNT_DAYS");
        Review review = calculator.review(snapshot, "demo", range(3, 5), range(1, 1), 20);
        assertThat(review.newMaterials()).isEmpty();
        assertThat(review.cohortMaterials().get("historical")).hasSize(1);
    }

    @Test
    void d065ConflictingOrLateCreationCannotDiscardExistingSpendAndNeverUseFutureMetadata() {
        Snapshot snapshot = complete(1, 4, List.of(
                simple(1, "conflict", "C", 1, "10"), simple(3, "conflict", "C", 2, "20"),
                simple(1, "late", "L", 2, "0"), simple(3, "late", "L", 2, "5"),
                simple(1, "future", "F", null, "3"), simple(4, "future", "F", 1, "7")));
        assertThat(calculator.top(snapshot, "demo", day(1), 20).rows().stream()
                .filter(row -> row.materialId().equals("conflict")).findFirst().orElseThrow().creationDate().status()).isEqualTo("VERIFIED");
        Top top = calculator.top(snapshot, "demo", day(3), 20);
        TopRow conflict = top.rows().getFirst();
        assertThat(conflict.creationDate()).isEqualTo(new CreationDate("VERIFIED", day(1), null));
        assertThat(conflict.lifetimeSpend().value()).isEqualByComparingTo("30");
        assertThat(conflict.lifetimeSpend().partialReasons()).doesNotContain("UNKNOWN_CREATION_DATE");
        assertThat(conflict.lifetimeCoverage().expectedDays()).isEqualTo(3);
        assertThat(top.rows().get(1).creationDate()).isEqualTo(new CreationDate("VERIFIED", day(1), null));
        assertThat(calculator.top(snapshot, "demo", day(1), 20).rows().stream()
                .filter(row -> row.materialId().equals("future")).findFirst().orElseThrow().creationDate().status()).isEqualTo("VERIFIED");
    }

    @Test
    void d065RatiosUseIdenticalPairedRowsInsteadOfMismatchedPartialColumnSums() {
        Snapshot snapshot = complete(1, 3, List.of(
                fact(1, "A", "A", 1, "10", "20", "100", "10", "1"),
                fact(2, "A", "A", 1, "90", null, null, "90", null),
                fact(3, "A", "A", 1, "20", "60", "200", null, "4")));
        TopRow row = calculator.top(snapshot, "demo", day(3), 20).rows().getFirst();
        assertThat(row.windowSpend().value()).isEqualByComparingTo("120");
        assertThat(row.recent3Roi().value()).isEqualByComparingTo(divide("80", "30"));
        assertThat(row.recent3Roi().partialReasons()).contains("MISSING_FIELD");
        assertThat(row.recent3Cpc().value()).isEqualByComparingTo("1");
        assertThat(row.recent3Cpm().value()).isEqualByComparingTo("100");
        assertThat(row.recent3Orders().value()).isEqualByComparingTo("5");
        Period period = calculator.review(snapshot, "demo", range(1, 3), range(1, 1), 20).periods().getFirst();
        assertMetric(period, "overall", "ctr", "0.1");
        assertMetric(period, "overall", "cvr", "0.1");
        assertThat(metric(period, "overall", "ctr").partialReasons()).contains("MISSING_FIELD");
    }

    @Test
    void d065AllUnknownInputsRemainUnknownWhileCoveredEmptyDayAllowsPartialZero() {
        Snapshot snapshot = complete(1, 2, List.of(fact(1, "A", "A", 1, "10", null, null, null, null)));
        TopRow row = calculator.top(snapshot, "demo", day(1), 20).rows().getFirst();
        assertThat(row.recent3Roi().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(row.recent3Roi().partialReasons()).contains("MISSING_FIELD", "MISSING_ACCOUNT_DAYS");
        Period period = calculator.review(snapshot, "demo", range(2, 3), range(3, 3), 20).periods().getFirst();
        assertMetric(period, "overall", "spend", "0");
        assertThat(metric(period, "overall", "spend").partialReasons()).contains("MISSING_ACCOUNT_DAYS");
    }

    @Test
    void d067ThreeDayDetailsKeepWindowEligibilityAndMatchedOperands() {
        Snapshot snapshot = complete(1, 5, List.of(
                fact(1, "A", "A", 1, "10", "20", "9000", "900", "90"),
                fact(2, "A", "A", 1, "10", "20", "100", "10", "1"),
                fact(3, "A", "A", 1, "20", "40", "200", "40", "8"),
                fact(3, "zero", "Z", 3, "0", "0", "999999", "999999", "999999"),
                fact(4, "A", "A", 1, "30", "60", "300", "30", "3"),
                fact(5, "A", "A", 1, "90", "180", "999999", "999999", "999999")));
        TopRow row = calculator.top(snapshot, "demo", day(4), 20).rows().getFirst();
        assertThat(row.recent3Impressions().value()).isEqualByComparingTo("600");
        assertThat(row.recent3Clicks().value()).isEqualByComparingTo("80");
        assertThat(row.recent3Ctr().value()).isEqualByComparingTo(divide("80", "600"));
        assertThat(row.recent3Cvr().value()).isEqualByComparingTo("0.15");
        assertThat(row.recent3Ctr().partial()).isFalse();
        assertThat(row.recent3Cpc().value()).isEqualByComparingTo("0.75");
        assertThat(row.recent3Cpm().value()).isEqualByComparingTo("100");
    }

    @Test
    void d067ThreeDayDetailsDistinguishPartialUnknownAndZeroDenominator() {
        Snapshot snapshot = complete(1, 3, List.of(
                fact(1, "A", "A", 1, "10", "20", "100", "10", "1"),
                fact(2, "A", "A", 1, "20", "40", null, "90", null),
                fact(3, "A", "A", 1, "30", "60", "300", null, "9")));
        TopRow row = calculator.top(snapshot, "demo", day(3), 20).rows().getFirst();
        assertThat(row.recent3Impressions().value()).isEqualByComparingTo("400");
        assertThat(row.recent3Clicks().value()).isEqualByComparingTo("100");
        assertThat(row.recent3Ctr().value()).isEqualByComparingTo("0.1");
        assertThat(row.recent3Cvr().value()).isEqualByComparingTo("0.1");
        assertThat(row.recent3Ctr().partialReasons()).contains("MISSING_FIELD");
        assertThat(row.recent3Impressions().partialReasons()).contains("MISSING_FIELD");
        TopRow unknown = calculator.top(complete(3, 3, List.of(
                fact(3, "U", "U", 3, "1", "2", null, null, null))), "demo", day(3), 20).rows().getFirst();
        assertThat(unknown.recent3Impressions().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(unknown.recent3Clicks().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(unknown.recent3Ctr().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(unknown.recent3Cvr().partialReasons()).contains("MISSING_FIELD", "MISSING_ACCOUNT_DAYS");
        TopRow zero = calculator.top(complete(1, 3, List.of(
                fact(3, "Z", "Z", 3, "1", "2", "0", "0", "0"))), "demo", day(3), 20).rows().getFirst();
        assertThat(zero.recent3Impressions().value()).isEqualByComparingTo("0");
        assertThat(zero.recent3Ctr().status()).isEqualTo(Status.ZERO_DENOMINATOR);
        assertThat(zero.recent3Cvr().status()).isEqualTo(Status.ZERO_DENOMINATOR);
    }

    @Test
    void d069OverallTopDetailsIncludePendingAndKeepSameMaterialPreviousSpend() {
        Snapshot snapshot = removeDay(complete(1, 5, List.of(
                fact(1, "pending", "P", null, "7", "14", "70", "7", "1"),
                fact(3, "pending", "P", null, "30", "60", "300", "30", "3"),
                fact(5, "pending", "P", null, "20", "80", "200", "10", "2"),
                simple(3, "new", "N", 3, "10"))), day(4));
        Review result = calculator.review(snapshot, "demo", range(3, 5), range(1, 2), 1);
        assertThat(result.topMaterials()).hasSize(1);
        ReviewMaterialRow pending = result.topMaterials().getFirst();
        assertThat(pending.materialId()).isEqualTo("pending");
        assertThat(pending.creationDate().status()).isEqualTo("VERIFIED");
        assertThat(pending.previousSpend().value()).isEqualByComparingTo("7");
        assertThat(pending.currentSpend().value()).isEqualByComparingTo("50");
        assertThat(pending.ctr().value()).isEqualByComparingTo("0.08");
        assertThat(pending.cvr().value()).isEqualByComparingTo("0.125");
        assertThat(pending.cpm().value()).isEqualByComparingTo("100");
        assertThat(pending.dailySpend()).extracting(DailySpend::date).containsExactly(day(3), day(4), day(5));
        assertThat(pending.dailySpend().get(1).spend().status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(pending.dailySpend().get(1).spend().partialReasons()).contains("MISSING_ACCOUNT_DAYS");
        assertThat(result.cohortMaterials().get("new")).extracting(ReviewMaterialRow::materialId).containsExactly("new");
        assertThat(result.cohortMaterials().get("historical")).extracting(ReviewMaterialRow::materialId).containsExactly("pending");
    }

    @Test
    void d073EarliestBusinessDateWinsAcrossSourceDisagreementAndAnchorDoesNotLeakFutureRows() {
        List<Fact> facts = List.of(simple(3, "A", "A", 4, "0"),
                simple(4, "A", "A", 2, "10"), simple(5, "A", "A", 1, "20"));
        assertThat(DashboardCalculator.resolveCreationDate(facts, day(3)))
                .isEqualTo(new CreationDate("VERIFIED", day(3), null));
        assertThat(DashboardCalculator.resolveCreationDate(facts, day(4)))
                .isEqualTo(new CreationDate("VERIFIED", day(2), null));
        assertThat(DashboardCalculator.resolveCreationDate(facts, day(5)))
                .isEqualTo(new CreationDate("VERIFIED", day(1), null));
        assertThat(DashboardCalculator.resolveCreationDate(facts, day(2)).status()).isEqualTo("PENDING");
    }

    @Test
    void d073AigcHasIndependentGroupAndNotApplicableCreationWithoutSyntheticPartial() {
        Snapshot snapshot = complete(1, 5, List.of(
                simple(1, "old", "ordinary AIGC named video", 1, "10"),
                simple(3, "old", "ordinary AIGC named video", 1, "10"),
                simple(3, "new", "new", 3, "20"),
                simple(3, "-2", "AIGC aggregate", null, "30"),
                simple(5, "-2", "AIGC aggregate", null, "5"),
                simple(3, "zz", "ZZ", 3, "7")));
        Review review = calculator.review(snapshot, "demo", range(3, 5), range(1, 2), 20);
        Period current = review.periods().getFirst();
        assertThat(current.groups()).extracting(Group::key).containsExactly("overall", "new", "historical", "aigc");
        assertThat(review.comparisons()).extracting(Group::key).containsExactly("overall", "new", "historical", "aigc");
        assertMetric(current, "overall", "spend", "72");
        assertMetric(current, "aigc", "spend", "35");
        assertMetric(current, "aigc", "materialCount", "1");
        assertMetric(current, "overall", "materialCount", "3");
        for (String key : List.of("spend", "materialCount")) {
            BigDecimal sum = List.of("new", "historical", "aigc").stream()
                    .map(group -> metric(current, group, key).value()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(metric(current, "overall", key).value());
        }
        assertThat(current.pendingMaterialCount()).isZero();
        assertThat(review.cohortMaterials().get("aigc")).extracting(ReviewMaterialRow::materialId).containsExactly("-2");
        TopRow row = calculator.top(snapshot, "demo", day(5), 20).rows().getFirst();
        assertThat(row.creationDate()).isEqualTo(new CreationDate("NOT_APPLICABLE", null, null));
        assertThat(row.lifetimeSpend().value()).isEqualByComparingTo("35");
        assertThat(row.lifetimeSpend().partial()).isFalse();
        assertThat(row.lifetimeCoverage().expectedDays()).isEqualTo(3);
        TopRow missing = calculator.top(removeDay(snapshot, day(4)), "demo", day(5), 20).rows().getFirst();
        assertThat(missing.lifetimeSpend().partialReasons()).containsExactly("MISSING_ACCOUNT_DAYS");
    }

    @Test
    void d074PeriodCpcUsesMatchedInputsAndAllGroupComparisons() {
        Snapshot snapshot = complete(1, 4, List.of(
                fact(1, "A", "A", 1, "10", "20", "100", "5", "1"),
                fact(3, "A", "A", 1, "30", "60", "300", "10", "1"),
                fact(4, "A", "A", 1, "90", "180", "900", null, "1"),
                fact(3, "N", "N", 3, "20", "40", "200", "10", "1"),
                fact(3, "-2", "AIGC", null, "10", "20", "100", "5", "1")));
        Review review = calculator.review(snapshot, "demo", range(3, 4), range(1, 2), 20);
        assertMetric(review.periods().getFirst(), "overall", "cpc", "2.4");
        assertMetric(review.periods().getFirst(), "historical", "cpc", "3");
        assertMetric(review.periods().getFirst(), "new", "cpc", "2");
        assertMetric(review.periods().getFirst(), "aigc", "cpc", "2");
        assertThat(metric(review.periods().getFirst(), "overall", "cpc").partialReasons()).contains("MISSING_FIELD");
        assertThat(review.comparisons()).allMatch(group -> group.metrics().containsKey("cpc"));
        assertThat(review.comparisons().getFirst().metrics().get("cpc").value()).isEqualByComparingTo("1.2");
        assertThat(review.comparisons().getFirst().metrics().get("cpc").partialReasons()).contains("MISSING_FIELD");
    }

    @Test
    void d074PeriodCpcDistinguishesUnknownZeroAndPartialCoverage() {
        Snapshot snapshot = complete(1, 2, List.of(
                fact(1, "A", "A", 1, "10", "20", "100", null, "1"),
                fact(2, "A", "A", 1, "10", "20", "100", "0", "1")));
        Review review = calculator.review(snapshot, "demo", range(1, 1), range(2, 3), 20);
        assertThat(metric(review.periods().getFirst(), "overall", "cpc").status()).isEqualTo(Status.UNKNOWN_INPUT);
        assertThat(metric(review.periods().get(1), "overall", "cpc").status()).isEqualTo(Status.ZERO_DENOMINATOR);
        assertThat(metric(review.periods().get(1), "overall", "cpc").partialReasons()).contains("MISSING_ACCOUNT_DAYS");
    }

    private static MetricArithmetic.Result metric(Period period, String key, String metric) {
        return period.groups().stream().filter(group -> group.key().equals(key)).findFirst().orElseThrow().metrics().get(metric);
    }

    private static void assertMetric(Period period, String key, String metric, String expected) {
        assertThat(metric(period, key, metric).status()).isEqualTo(Status.VALID);
        assertThat(metric(period, key, metric).value()).isEqualByComparingTo(expected);
    }

    private static Fact simple(int date, String id, String name, Integer created, String spend) {
        BigDecimal money = decimal(spend);
        return new Fact(day(date), id, name, created == null ? null : day(created), money,
                money == null ? null : money.multiply(decimal("2")), decimal("100"), decimal("10"), decimal("1"));
    }

    private static Fact fact(int date, String id, String name, Integer created, String spend, String gmv,
                             String impressions, String clicks, String orders) {
        return new Fact(day(date), id, name, created == null ? null : day(created), decimal(spend), decimal(gmv),
                decimal(impressions), decimal(clicks), decimal(orders));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String divide(String numerator, String denominator) {
        return decimal(numerator).divide(decimal(denominator), MathContext.DECIMAL128).toPlainString();
    }

    private static LocalDate day(int number) {
        return LocalDate.of(2026, 1, number);
    }

    private static DateRange range(int from, int to) {
        return new DateRange(day(from), day(to));
    }

    private static Snapshot complete(int from, int to, List<Fact> facts) {
        return complete(day(from), day(to), facts);
    }

    private static Snapshot complete(LocalDate from, LocalDate to, List<Fact> facts) {
        List<DayEvidence> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(new DayEvidence(date, days.size() + 1, "synthetic-hash-" + date, "SYNTHETIC"));
        }
        return new Snapshot("synthetic-account", List.copyOf(days), facts);
    }

    private static Snapshot removeDay(Snapshot snapshot, LocalDate date) {
        return new Snapshot(snapshot.accountId(), snapshot.days().stream().filter(day -> !day.date().equals(date)).toList(),
                snapshot.facts().stream().filter(fact -> !fact.date().equals(date)).toList());
    }
}
