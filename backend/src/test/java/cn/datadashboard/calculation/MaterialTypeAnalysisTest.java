package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class MaterialTypeAnalysisTest {
    @Test void completeOverallDetailsIncludeMaterialsBeyondTwoHundredWithoutChangingReviewOrFacts() {
        List<Fact> facts=new ArrayList<>();
        for(int i=1;i<=230;i++)facts.add(row(5,"SYNTHETIC_"+i,"原创示例"+i,5,231-i,500));
        facts.add(new Fact(day(5),"-2","AIGC集合",null,BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,BigDecimal.ONE));
        var input=prepared(facts);
        var before=calculator.reviewWithEnhancedMaterialTypesPrepared(input,"synthetic",range(5,5),range(1,4),10);
        var complete=calculator.reviewMaterialsPrepared(input,"synthetic",range(5,5),range(1,4));
        assertThat(complete.rows()).hasSize(231);
        assertThat(complete.rows().subList(0,10)).isEqualTo(before.topMaterials());
        assertThat(complete.rows()).anyMatch(r->r.materialId().equals("SYNTHETIC_230"));
        assertThat(complete.rows().stream().filter(r->r.materialId().equals("-2")).findFirst().orElseThrow().creationDate().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(complete.snapshotFingerprint()).isEqualTo(before.snapshotFingerprint());
        assertThat(calculator.reviewWithEnhancedMaterialTypesPrepared(input,"synthetic",range(5,5),range(1,4),10)).isEqualTo(before);
        assertThat(input.snapshot().facts()).isEqualTo(facts);
    }
    private final DashboardCalculator calculator = new DashboardCalculator(java.time.Clock.fixed(
            java.time.Instant.parse("2026-02-01T00:00:00Z"), java.time.ZoneOffset.UTC));
    private static LocalDate day(int value) { return LocalDate.of(2026, 1, value); }
    private static DateRange range(int start, int end) { return new DateRange(day(start), day(end)); }
    private static Fact row(int stat, String id, String name, int first, int spend, int gmv) {
        return new Fact(day(stat), id, name, day(first), BigDecimal.valueOf(spend), BigDecimal.valueOf(gmv),
                BigDecimal.valueOf(1000), BigDecimal.valueOf(100), BigDecimal.valueOf(10));
    }
    private static DashboardCalculator.PreparedSnapshot prepared(List<Fact> rows) {
        List<DayEvidence> days = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(i -> new DayEvidence(day(i), i, "synthetic-" + i, "SYNTHETIC")).toList();
        return DashboardCalculator.prepare(new Snapshot("synthetic-account", days, rows));
    }
    private static BigDecimal metric(MaterialTypeReview value, String type, String field) {
        return value.periods().getFirst().groups().stream().filter(g -> g.key().equals(type)).findFirst().orElseThrow().metrics().get(field).value();
    }
    private static BigDecimal ai(MaterialTypeReview value, String type, String field) {
        return value.ai().periods().getFirst().groups().stream().filter(g -> g.key().equals(type)).findFirst().orElseThrow().metrics().get(field).value();
    }

    @Test void independentFirstDateWindowsIncludeOlderMaterialsWithoutChangingOriginalReview() {
        var snapshot = prepared(List.of(row(5,"A","原创-AI商单",1,100,300), row(6,"B","混剪-ai部门",2,200,400),
                row(7,"C","AIGC商单部门",3,50,200), row(8,"D","AI无标签",4,25,50),
                row(9,"E","原创AIR商单",1,300,900), row(10,"F","原创AIR-AI部门",4,75,150),
                row(3,"A","原创-AI商单",1,10,20)));
        var original = calculator.reviewWithMaterialTypesPrepared(snapshot,"synthetic",range(5,10),range(1,4),10);
        var stats = snapshot.stats(); var facts = snapshot.snapshot().facts();
        var value = calculator.materialTypesPrepared(snapshot,"synthetic",range(5,10),range(1,4),range(1,4),range(1,4));
        assertThat(value.schemaVersion()).isEqualTo("material-type-review-2.0.0");
        assertThat(value.periods().getFirst().testRange()).isEqualTo(range(1,4));
        assertThat(metric(value,"original","spend")).isEqualByComparingTo("475");
        assertThat(metric(value,"mixed","spend")).isEqualByComparingTo("200");
        assertThat(metric(value,"other","spend")).isEqualByComparingTo("75");
        assertThat(ai(value,"ai","spend")).isEqualByComparingTo("450");
        assertThat(ai(value,"business","spend")).isEqualByComparingTo("150");
        assertThat(ai(value,"department","spend")).isEqualByComparingTo("325");
        assertThat(ai(value,"ai","materialCount")).isEqualByComparingTo("5");
        assertThat(value.ai().materials().get("business")).extracting(MaterialTypeRow::materialId).containsExactly("A", "C");
        assertThat(value.ai().materials().get("department")).extracting(MaterialTypeRow::materialId).containsExactly("B", "F", "C");
        assertThat(value.ai().materials().get("department")).extracting(MaterialTypeRow::rank).containsExactly(1, 2, 3);
        assertThat(value.ai().materials().get("business").getFirst().previousSpend().value()).isEqualByComparingTo("10");
        assertThat(value.ai().materials().get("business").getFirst().dailySpend()).hasSize(6);
        assertThat(value.ai().materials().get("business").getFirst().creationDate().date()).isEqualTo(day(1));
        assertThat(value.ai().materials().values().stream().flatMap(List::stream)).noneMatch(r -> r.materialId().equals("D") || r.materialId().equals("E"));
        assertThat(ai(value,"ai","roi")).isEqualByComparingTo(BigDecimal.valueOf(1100).divide(BigDecimal.valueOf(450),java.math.MathContext.DECIMAL128));
        assertThat(value.materials().get("original").stream().map(MaterialTypeRow::previousSpend).map(MetricArithmetic.Result::value)).contains(new BigDecimal("10"));
        assertThat(snapshot.snapshot().facts()).isEqualTo(facts); assertThat(snapshot.stats()).isEqualTo(stats);
        assertThat(calculator.reviewWithMaterialTypesPrepared(snapshot,"synthetic",range(5,10),range(1,4),10)).isEqualTo(original);
    }

    @Test void excludesOnlyAirOccurrencesWithCaseInsensitiveAiAndRetainsOtherAiOccurrences() {
        List<String> names = List.of("AIR商单", "air部门", "FAIR商单", "chair部门", "AAIRI商单",
                "AIGC商单", "原创ai部门", "AIR-AI商单", "混剪aI商单部门", "AI无标签");
        List<Fact> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) rows.add(row(5,"SYNTHETIC_"+i,names.get(i),1,10,20));
        var value = calculator.materialTypesPrepared(prepared(rows),"synthetic",range(5,5),range(1,1),range(1,1),range(1,1));
        assertThat(ai(value,"ai","spend")).isEqualByComparingTo("50");
        assertThat(ai(value,"business","spend")).isEqualByComparingTo("30");
        assertThat(ai(value,"department","spend")).isEqualByComparingTo("20");
        assertThat(metric(value,"original","spend")).isEqualByComparingTo("10");
        assertThat(metric(value,"mixed","spend")).isEqualByComparingTo("10");
        assertThat(metric(value,"other","spend")).isEqualByComparingTo("80");
        assertThat(value.ai().materials().get("business")).extracting(MaterialTypeRow::materialId).containsExactly("SYNTHETIC_5", "SYNTHETIC_7", "SYNTHETIC_8");
        assertThat(value.ai().materials().get("department")).extracting(MaterialTypeRow::materialId).containsExactly("SYNTHETIC_6", "SYNTHETIC_8");
    }

    @Test void eachPeriodUsesItsOwnInclusiveTestWindowAndCoverageIsOnlyTheStatisticsWindow() {
        var snapshot = prepared(List.of(row(5,"A","原创AI商单",1,30,60), row(6,"B","混剪AI部门",2,40,80),
                row(2,"A","原创AI商单",1,10,20), row(3,"B","混剪AI部门",2,20,40)));
        var value = calculator.materialTypesPrepared(snapshot,"synthetic",range(5,6),range(1,1),range(2,3),range(2,2));
        assertThat(ai(value,"ai","spend")).isEqualByComparingTo("30");
        assertThat(value.ai().periods().get(1).groups().getFirst().metrics().get("spend").value()).isEqualByComparingTo("20");
        assertThat(value.periods().getFirst().coverage().expectedDays()).isEqualTo(2);
        assertThat(value.materials().get("original").getFirst().previousSpend().value()).isEqualByComparingTo("0");
        assertThat(value.ai().materials().get("business").getFirst().previousSpend().value()).isEqualByComparingTo("0");
        assertThat(value.ai().materials().get("department")).isEmpty();
    }

    @Test void unavailableCoverageRemainsUnknownAndLegacyAiIsAbsentRatherThanFabricated() {
        var snapshot = prepared(List.of());
        var value = calculator.materialTypesPrepared(snapshot,"synthetic",range(15,16),range(1,4),range(1,2),range(1,2));
        assertThat(value.ai().periods().getFirst().groups().getFirst().metrics().get("spend").status()).isEqualTo(MetricArithmetic.Status.UNKNOWN_INPUT);
        assertThat(calculator.reviewWithMaterialTypesPrepared(snapshot,"synthetic",range(1,2),range(3,4),10).materialTypes().ai()).isNull();
    }
}
