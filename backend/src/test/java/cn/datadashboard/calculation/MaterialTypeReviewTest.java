package cn.datadashboard.calculation;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;

class MaterialTypeReviewTest {
    private final DashboardCalculator calc = new DashboardCalculator(Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"),ZoneOffset.UTC));
    private static LocalDate d(int day) { return LocalDate.of(2026,1,day); }
    private static BigDecimal n(String v) { return v == null ? null : new BigDecimal(v); }
    private static Fact f(int day,String id,String name,int first,String spend,String gmv,String impressions,String clicks,String orders) {
        return new Fact(d(day),id,name,d(first),n(spend),n(gmv),n(impressions),n(clicks),n(orders));
    }
    private static Fact f(int day,String id,String name,int first,int spend) { return f(day,id,name,first,""+spend,""+(spend*2),"1000","10","1"); }
    private static Snapshot snapshot(List<Fact> rows) {
        return new Snapshot("test-account", java.util.stream.IntStream.rangeClosed(1,20).mapToObj(i -> new DayEvidence(d(i),i,"test-"+i,"SYNTHETIC")).toList(),rows);
    }
    private Review review(Snapshot snapshot) { return calc.reviewWithMaterialTypesPrepared(DashboardCalculator.prepare(snapshot),"demo",new DateRange(d(5),d(10)),new DateRange(d(1),d(4)),10); }
    private static MetricArithmetic.Result m(Review r,int period,String type,String metric) { return r.materialTypes().periods().get(period).groups().stream().filter(g -> g.key().equals(type)).findFirst().orElseThrow().metrics().get(metric); }

    @Test void bothDatesInclusiveWithIndependentPriorCohortAndDistinctIds() {
        Review r = review(snapshot(List.of(f(5,"A","原创混剪",5,100),f(10,"A","原创混剪",5,200),f(10,"B","混剪",10,40),f(6,"C","AI",6,60),f(6,"old","原创",1,9000),f(11,"future","原创",11,8000),f(4,"prior","原创",1,25))));
        assertThat(m(r,0,"original","spend").value()).isEqualByComparingTo("300");
        assertThat(m(r,0,"original","materialCount").value()).isEqualByComparingTo("1");
        assertThat(m(r,0,"mixed","spend").value()).isEqualByComparingTo("40");
        assertThat(m(r,0,"other","spend").value()).isEqualByComparingTo("60");
        assertThat(m(r,1,"original","spend").value()).isEqualByComparingTo("25");
        assertThat(r.materialTypes().comparisons().getFirst().metrics().get("spend").value()).isEqualByComparingTo("12");
        var row = r.materialTypes().materials().get("original").getFirst();
        assertThat(row.previousSpend().value()).isEqualByComparingTo("0");
        assertThat(row.dailySpend()).hasSize(6);
        assertThat(row.dailySpend().getFirst().spend().value()).isEqualByComparingTo("100");
        assertThat(row.dailySpend().getLast().spend().value()).isEqualByComparingTo("200");
    }
    @Test void ratiosUsePairedSumsAndNeverAverageDailyRatios() {
        Review r = review(snapshot(List.of(f(5,"A","原创",5,"100","300","1000","10","2"),f(6,"A","原创",5,"300","300","9000","90","8"),f(7,"B","原创",7,"50",null,"500",null,null))));
        assertThat(m(r,0,"original","spend").value()).isEqualByComparingTo("450");
        assertThat(m(r,0,"original","roi").value()).isEqualByComparingTo("1.5");
        assertThat(m(r,0,"original","roi").partial()).isTrue();
        assertThat(m(r,0,"original","cpc").value()).isEqualByComparingTo("4");
        assertThat(m(r,0,"original","ctr").value()).isEqualByComparingTo("0.01");
        assertThat(m(r,0,"original","cvr").value()).isEqualByComparingTo("0.1");
    }
    @Test void dimensionNameAndFirstDateAreAsOfPeriodEnd() {
        Review r = review(snapshot(List.of(f(5,"A","混剪",5,100),f(12,"A","原创",1,100),f(6,"B","AI",6,10),f(10,"B","原创",6,20))));
        assertThat(m(r,0,"mixed","spend").value()).isEqualByComparingTo("100");
        assertThat(m(r,0,"original","spend").value()).isEqualByComparingTo("30");
        assertThat(r.materialTypes().materials().get("mixed").getFirst().creationDate().date()).isEqualTo(d(5));
    }
    @Test void otherIsTheRemainderOfTheSameDualDatePopulationWithoutAnOtherNameKeyword() {
        Review r = review(snapshot(List.of(
                f(5,"A","原创混剪",5,"100","500","1000","10","2"),
                f(6,"B","混剪",6,"200","600","2000","20","2"),
                f(7,"C","产品展示",7,"30","90","1000","20","1"),
                f(8,"D","达人评测",8,"70","210","3000","30","4"),
                f(8,"old","产品展示历史",1,9000),
                f(11,"outside","达人评测",11,8000),
                f(9,"-2","AIGC动态创意素材集合",9,7000))));
        // Independently known eligible population: 400 spend, four distinct materials.
        assertThat(m(r,0,"other","spend").value()).isEqualByComparingTo(
                n("400").subtract(m(r,0,"original","spend").value()).subtract(m(r,0,"mixed","spend").value()));
        assertThat(m(r,0,"other","materialCount").value()).isEqualByComparingTo(
                n("4").subtract(m(r,0,"original","materialCount").value()).subtract(m(r,0,"mixed","materialCount").value()));
        assertThat(r.materialTypes().materials().get("other")).extracting(MaterialTypeRow::materialName)
                .containsExactly("达人评测", "产品展示");
        // Rates are recomputed from remaining inputs, never subtracted from overall rates.
        assertThat(m(r,0,"other","roi").value()).isEqualByComparingTo("3");
        assertThat(m(r,0,"other","ctr").value()).isEqualByComparingTo("0.0125");
        assertThat(m(r,0,"other","cvr").value()).isEqualByComparingTo("0.1");
        assertThat(m(r,0,"other","cpm").value()).isEqualByComparingTo("25");
        assertThat(m(r,0,"other","cpc").value()).isEqualByComparingTo("2");
    }
    @Test void zzKeepsMoneyAndAigcHasNoDateWhileZeroSpendIsIneligible() {
        Review r = review(snapshot(List.of(f(5,"A","原创zz",5,100),f(6,"-2","AIGC",6,500),f(7,"Z","原创",7,0))));
        assertThat(m(r,0,"original","spend").value()).isEqualByComparingTo("100");
        assertThat(m(r,0,"original","materialCount").value()).isEqualByComparingTo("0");
        assertThat(m(r,0,"other","spend").value()).isEqualByComparingTo("0");
        assertThat(m(r,0,"other","roi").status()).isEqualTo(MetricArithmetic.Status.ZERO_DENOMINATOR);
        assertThat(r.materialTypes().periods().getFirst().excludedUndatedMaterials()).isEqualTo(1);
    }
    @Test void missingDaysAndUnknownSpendAreNotZeros() {
        Snapshot all = snapshot(List.of(f(5,"A","原创",5,null,"1","2","3","4"),f(5,"B","混剪",5,10)));
        Snapshot partial = new Snapshot(all.accountId(),all.days().stream().filter(e -> !e.date().equals(d(7))).toList(),all.facts());
        Review r = review(partial);
        assertThat(m(r,0,"original","spend").status()).isEqualTo(MetricArithmetic.Status.UNKNOWN_INPUT);
        assertThat(m(r,0,"original","spend").partialReasons()).contains("MISSING_ACCOUNT_DAYS","UNKNOWN_SPEND");
        assertThat(r.materialTypes().materials().get("original")).isEmpty();
        assertThat(r.materialTypes().materials().get("mixed").getFirst().dailySpend().get(2).spend().status()).isEqualTo(MetricArithmetic.Status.UNKNOWN_INPUT);
        Review empty = review(new Snapshot("empty",List.of(),List.of()));
        assertThat(m(empty,0,"other","spend").status()).isEqualTo(MetricArithmetic.Status.UNKNOWN_INPUT);
    }
    @Test void enrichedResponsePreservesAllExistingBlocksAndPreparedSnapshot() {
        var prepared = DashboardCalculator.prepare(MaterialTypePreviewMain.fixture());
        var current = new DateRange(d(1),d(10));
        var prior = new DateRange(LocalDate.of(2025,12,22),LocalDate.of(2025,12,31));
        Review before = calc.reviewPrepared(prepared,"demo",current,prior,10);
        Review enriched = calc.reviewWithMaterialTypesPrepared(prepared,"demo",current,prior,10);
        assertThat(enriched).usingRecursiveComparison().ignoringFields("materialTypes").isEqualTo(before);
        assertThat(calc.reviewWithMaterialTypesPrepared(prepared,"demo",current,prior,10)).isEqualTo(enriched);
        assertThat(enriched.materialTypes().materials().get("original")).hasSize(14);
    }
}
