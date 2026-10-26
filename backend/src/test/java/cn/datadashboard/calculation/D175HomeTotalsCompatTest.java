package cn.datadashboard.calculation;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;
import static cn.datadashboard.calculation.MetricArithmetic.*;

/** Synthetic, dependency-free checks; no filesystem, database or network access. */
public final class D175HomeTotalsCompatTest {

    @org.junit.jupiter.api.Test
    void eachAddedColumnUsesItsOwnKnownRowsAndExactDecimals() {
        Review r = review(new Case("independent-fields", snapshot(Set.of(), List.of(
                fact(8, "A", "A", 8, "1", "0.1234567890123456789", null, "0"),
                fact(9, "B", "B", 8, "2", null, "12345678901234567890", "3"),
                fact(10, "C", "C", 8, "3", "0.0000000000000000001", "10", null)))));
        value(r, "CURRENT", "overall", "gmv", "0.1234567890123456790", true, "MISSING_FIELD");
        value(r, "CURRENT", "overall", "impressions", "12345678901234567900", true, "MISSING_FIELD");
        value(r, "CURRENT", "overall", "clicks", "3", true, "MISSING_FIELD");
        value(r, "CURRENT", "overall", "spend", "6", false);
    }

    @org.junit.jupiter.api.Test
    void allTwelveSyntheticScenariosAndAdditiveShapes() throws Exception {
        assertions = 0;
        main(new String[0]);
    }

    /** Frozen pre-change calculator SHA: 4f7468335efd7da9644b70ab430632771461c05373ba8a9f480cbd85762943cb. */
    @org.junit.jupiter.api.Test
    void completeLegacyOutputsRemainIdenticalToPreChangeCalculator() throws Exception {
        List<String> actual = new ArrayList<>();
        for (Case c : cases()) {
            String oldFields = canonical(List.of(review(c), CALC.top(c.snapshot(), "synthetic-home", day(14), 3)));
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldFields.getBytes(StandardCharsets.UTF_8)));
            actual.add(c.name() + " " + sha + " " + oldFields.length());
        }
        org.junit.jupiter.api.Assertions.assertEquals("normal-totals-groups 8eecf2ed3d05329a1a0e3e342818ea3acf817ca65eaa1ce6fcb63cd0bc5c9049 27102\npartial-fields 39350ef3e3c938d7fffb588b9d1fbbc6908c10c7ccc29d4235eefbb186cf5dbb 22660\nall-fields-unknown f083c1bc9577991605b255fb98347dbb8ceb994566d4a27f861bf4d5b7d0b8c4 18651\nmissing-account-day 567eaafe5c07d6d7dfed580c978c9a54ff1e05f89f66ce34b6bb4f68b84c6a34 20011\nunknown-spend 5616fad45c25d489c0b8685f609519c36c530c9e75d876aef70036ee20e44e24 19111\nall-spend-unknown 700eb0b9d53d6b1187414b7167f083a4d884bc0b858c8612c58d751fce12a33c 15316\nzero-negative-excluded b3316de07ed9abcba8d6a463d0dc5e0901be7f5bd08276e8349c4ee41a133e49 18019\nall-zero f26a32c7f6bc820869a1d7921548292233c16257b569b687e5ab44679d77c197 14306\nno-current-coverage 1e5e47b6051590f0d300c4581407cf7d641fca6cb06e451094dd9ba787d8cf97 16089\npartial-covered-empty 6b20e73e3d2f3933c474aee712988be51047d143c9c7cdb6aa395ae3c644397e 16065\nover-200-top3-not-total 5519ce21952f5c8669b2a8eb934c035a753d684797045c8e2426a38deb1094f7 370559\ntext-ties-aigc-zz a919b7e6121e86c8030bb09cb251a285e85703ba76a01b6ba0f7246a7ef8a20e 25473", String.join("\n", actual));
    }

    private static final DashboardCalculator CALC = new DashboardCalculator(
            Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC));
    private static final Set<String> ADDED = Set.of("gmv", "impressions", "clicks");
    private static final Set<String> LEGACY = Set.of("spend", "materialCount", "ctr", "cvr", "spendShare", "roi", "cpm", "cpc");
    private static final DateRange CURRENT = new DateRange(day(8), day(14));
    private static final DateRange PREVIOUS = new DateRange(day(1), day(7));
    private static int assertions;
    private record Case(String name, Snapshot snapshot) {}

    public static void main(String[] args) throws Exception {
        List<Case> cases = cases();
        if (args.length == 1 && args[0].equals("--snapshot")) {
            for (Case c : cases) {
                String oldFields = canonical(List.of(review(c), CALC.top(c.snapshot(), "synthetic-home", day(14), 3)));
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldFields.getBytes(StandardCharsets.UTF_8)));
                System.out.println(c.name() + " " + sha + " " + oldFields.length());
            }
            return;
        }
        normalTotalsAndGroups(cases.get(0));
        partialFields(cases.get(1));
        unknownFields(cases.get(2));
        missingDay(cases.get(3));
        unknownSpend(cases.get(4));
        allUnknownSpend(cases.get(5));
        zeroSpendExcluded(cases.get(6));
        allZero(cases.get(7));
        noCoverage(cases.get(8));
        knownEmptyPartial(cases.get(9));
        topThreeIsNotTotal(cases.get(10));
        tiesAigcAndZz(cases.get(11));
        for (Case c : cases) additiveShapeOnly(c);
        System.out.println("PASS scenarios=12 additive_shape_cases=12 assertions=" + assertions);
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("normal-totals-groups", snapshot(Set.of(), List.of(
                fact(2,"A","A",1,"4","8","40","4"),
                fact(8,"A","A",1,"10","20","100","10"),
                fact(9,"B","B",8,"20","60","200","20"),
                fact(10,"-2","AIGC aggregate",null,"5","10","50","5"),
                fact(11,"ZZ","sample_ZZ",11,"3","6","30","3"),
                fact(15,"future","future",15,"999","999","999","999")))));
        cases.add(new Case("partial-fields", snapshot(Set.of(), List.of(
                fact(8,"A","A",8,"10","20","100","10"),
                fact(9,"B","B",8,"20",null,null,null)))));
        cases.add(new Case("all-fields-unknown", snapshot(Set.of(), List.of(
                fact(8,"A","A",8,"10",null,null,null)))));
        cases.add(new Case("missing-account-day", snapshot(Set.of(10), List.of(
                fact(8,"A","A",8,"10","20","100","10")))));
        cases.add(new Case("unknown-spend", snapshot(Set.of(), List.of(
                fact(8,"A","A",8,"10","20","100","10"),
                fact(9,"B","B",8,null,"999","999","999")))));
        cases.add(new Case("all-spend-unknown", snapshot(Set.of(), List.of(
                fact(8,"A","A",8,null,"999","999","999")))));
        cases.add(new Case("zero-negative-excluded", snapshot(Set.of(), List.of(
                fact(8,"A","A",8,"10","20","100","10"),
                fact(9,"zero","zero",8,"0","9999","9999","9999"),
                fact(10,"negative","negative",8,"-5","9999","9999","9999")))));
        cases.add(new Case("all-zero", snapshot(Set.of(), List.of(
                fact(8,"zero","zero",8,"0","9999","9999","9999")))));
        cases.add(new Case("no-current-coverage", snapshot(Set.of(8,9,10,11,12,13,14), List.of())));
        cases.add(new Case("partial-covered-empty", snapshot(Set.of(9,10,11,12,13,14), List.of())));
        List<Fact> many = new ArrayList<>();
        for (int i=1; i<=205; i++) many.add(fact(8,"m"+i,"M",8,"1","2","10","1"));
        many.add(fact(14,"late-high","H",8,"1000","2000","10000","1000"));
        cases.add(new Case("over-200-top3-not-total", snapshot(Set.of(), many)));
        cases.add(new Case("text-ties-aigc-zz", snapshot(Set.of(), List.of(
                fact(8,"2","normal",8,"10","20","100","10"),
                fact(8,"10","ZZ",8,"10","20","100","10"),
                fact(8,"-2","AIGC aggregate",null,"10","20","100","10")))));
        return cases;
    }

    private static void normalTotalsAndGroups(Case c) {
        Review r=review(c);
        value(r, "CURRENT", "overall", "spend", "38", false);
        value(r, "CURRENT", "overall", "gmv", "96", false);
        value(r, "CURRENT", "overall", "impressions", "380", false);
        value(r, "CURRENT", "overall", "clicks", "38", false);
        value(r, "CURRENT", "historical", "gmv", "20", false);
        value(r, "CURRENT", "new", "gmv", "66", false);
        value(r, "CURRENT", "aigc", "gmv", "10", false);
        value(r, "PREVIOUS", "overall", "gmv", "8", false);
        equal(r.topMaterials().stream().map(ReviewMaterialRow::materialId).toList(),List.of("B","A","-2"),"normal rank");
    }
    private static void partialFields(Case c) {
        Review r=review(c);
        value(r,"CURRENT","overall","gmv","20",true,"MISSING_FIELD");
        value(r,"CURRENT","overall","impressions","100",true,"MISSING_FIELD");
        value(r,"CURRENT","overall","clicks","10",true,"MISSING_FIELD");
        value(r,"CURRENT","overall","spend","30",false);
        equal(metric(r,"CURRENT","overall","roi").value(),new BigDecimal("2"),"paired ROI not inverted into GMV");
    }
    private static void unknownFields(Case c) {
        for(String key:ADDED) unknown(review(c),key,"MISSING_FIELD");
    }
    private static void missingDay(Case c) {
        Review r=review(c);
        value(r,"CURRENT","overall","gmv","20",true,"MISSING_ACCOUNT_DAYS");
        value(r,"CURRENT","overall","impressions","100",true,"MISSING_ACCOUNT_DAYS");
        value(r,"CURRENT","overall","clicks","10",true,"MISSING_ACCOUNT_DAYS");
        equal(r.periods().getFirst().coverage().missingDates(),List.of(day(10)),"missing day evidence");
    }
    private static void unknownSpend(Case c) {
        Review r=review(c);
        value(r,"CURRENT","overall","gmv","20",true,"UNKNOWN_SPEND");
        value(r,"CURRENT","overall","impressions","100",true,"UNKNOWN_SPEND");
        value(r,"CURRENT","overall","clicks","10",true,"UNKNOWN_SPEND");
        equal(r.metricsState(),"PARTIAL","unknown spend ranking caution");
        equal(r.topMaterials().stream().map(ReviewMaterialRow::materialId).toList(),List.of("A"),"known rank retained");
    }
    private static void allUnknownSpend(Case c) {
        for(String key:ADDED) unknown(review(c),key,"UNKNOWN_SPEND");
    }
    private static void zeroSpendExcluded(Case c) {
        Review r=review(c);
        value(r,"CURRENT","overall","gmv","20",false);
        value(r,"CURRENT","overall","impressions","100",false);
        value(r,"CURRENT","overall","clicks","10",false);
        equal(r.topMaterials().stream().map(ReviewMaterialRow::materialId).toList(),List.of("A"),"nonpositive rows excluded");
    }
    private static void allZero(Case c) {
        Review r=review(c);
        for(String key:ADDED) value(r,"CURRENT","overall",key,"0",false);
        equal(r.topMaterials().size(),0,"zero is not missing rank");
    }
    private static void noCoverage(Case c) {
        for(String key:ADDED) unknown(review(c),key,"MISSING_ACCOUNT_DAYS");
    }
    private static void knownEmptyPartial(Case c) {
        for(String key:ADDED) value(review(c),"CURRENT","overall",key,"0",true,"MISSING_ACCOUNT_DAYS");
    }
    private static void topThreeIsNotTotal(Case c) {
        Review r=review(c);
        value(r,"CURRENT","overall","spend","1205",false);
        value(r,"CURRENT","overall","gmv","2410",false);
        value(r,"CURRENT","overall","impressions","12050",false);
        value(r,"CURRENT","overall","clicks","1205",false);
        equal(r.topMaterials().stream().map(ReviewMaterialRow::materialId).toList(),List.of("late-high","m1","m10"),"global top3 after 205 rows");
        BigDecimal sum=r.topMaterials().stream().map(row->row.currentSpend().value()).reduce(BigDecimal.ZERO,BigDecimal::add);
        equal(sum,new BigDecimal("1002"),"TOP3 differs from total 1205");
        equal(r.cohortMaterials().get("new").size(),206,"full cohort remains untruncated");
    }
    private static void tiesAigcAndZz(Case c) {
        Review r=review(c);
        equal(r.topMaterials().stream().map(ReviewMaterialRow::materialId).toList(),List.of("-2","10","2"),"text tie ordering");
        equal(r.topMaterials().getFirst().creationDate().status(),"NOT_APPLICABLE","AIGC creation remains NA");
        value(r,"CURRENT","overall","gmv","60",false);
        value(r,"CURRENT","overall","materialCount","2",false);
    }
    private static void additiveShapeOnly(Case c) {
        Review r=review(c);
        Set<String> expected=new HashSet<>(LEGACY); expected.addAll(ADDED);
        for(Period p:r.periods()) for(Group g:p.groups()) equal(g.metrics().keySet(),expected,"exact period keys: "+c.name());
        for(Group g:r.comparisons()) equal(g.metrics().keySet(),LEGACY,"comparisons stay unchanged: "+c.name());
        equal(r.schemaVersion(),"dashboard-query-1.7.0","schema unchanged");
    }
    private static Review review(Case c) { return CALC.review(c.snapshot(),"synthetic-home",CURRENT,PREVIOUS,3); }
    private static Result metric(Review r,String period,String group,String key) {
        return r.periods().stream().filter(p->p.key().equals(period)).findFirst().orElseThrow()
                .groups().stream().filter(g->g.key().equals(group)).findFirst().orElseThrow().metrics().get(key);
    }
    private static void value(Review r,String p,String g,String key,String expected,boolean partial,String... reasons) {
        Result result=Objects.requireNonNull(metric(r,p,g,key),"missing "+key);
        equal(result.status(),Status.VALID,key+" status");
        equal(result.value(),new BigDecimal(expected),key+" value");
        equal(result.partial(),partial,key+" partial");
        equal(new HashSet<>(result.partialReasons()),Set.of(reasons),key+" reasons");
    }
    private static void unknown(Review r,String key,String... reasons) {
        Result result=Objects.requireNonNull(metric(r,"CURRENT","overall",key));
        equal(result.status(),Status.UNKNOWN_INPUT,key+" unknown status");
        equal(result.value(),null,key+" unknown value");
        equal(result.partial(),true,key+" unknown partial");
        equal(new HashSet<>(result.partialReasons()),Set.of(reasons),key+" unknown reasons");
    }
    private static void equal(Object actual,Object expected,String message) {
        assertions++;
        boolean same=actual instanceof BigDecimal a && expected instanceof BigDecimal b ? a.compareTo(b)==0 : Objects.equals(actual,expected);
        if(!same) throw new AssertionError(message+": expected="+expected+" actual="+actual);
    }
    private static LocalDate day(int n) { return LocalDate.of(2026,10,n); }
    private static BigDecimal decimal(String value) { return value==null ? null : new BigDecimal(value); }
    private static Fact fact(int date,String id,String name,Integer created,String spend,String gmv,String impressions,String clicks) {
        return new Fact(day(date),id,name,created==null?null:day(created),decimal(spend),decimal(gmv),decimal(impressions),decimal(clicks),BigDecimal.ONE);
    }
    private static Snapshot snapshot(Set<Integer> missing,List<Fact> facts) {
        List<DayEvidence> days=new ArrayList<>();
        for(int n=1;n<=15;n++) if(!missing.contains(n)) days.add(new DayEvidence(day(n),n,"a".repeat(64),"MCP_AUTO"));
        return new Snapshot("synthetic-account",List.copyOf(days),List.copyOf(facts));
    }
    /** Complete legacy object graph, only the three additive map keys omitted. */
    private static String canonical(Object value) throws Exception {
        if(value==null) return "null";
        if(value instanceof Map<?,?> map) {
            List<String> keys=map.keySet().stream().map(Object::toString).filter(k->!ADDED.contains(k)).sorted().toList();
            StringBuilder s=new StringBuilder("{");
            for(String key:keys) s.append(key.length()).append(':').append(key).append('=').append(canonical(map.get(key))).append(';');
            return s.append('}').toString();
        }
        if(value instanceof List<?> list) {
            StringBuilder s=new StringBuilder("["); for(Object item:list) s.append(canonical(item)).append(';'); return s.append(']').toString();
        }
        if(value.getClass().isRecord()) {
            StringBuilder s=new StringBuilder(value.getClass().getSimpleName()).append('(');
            for(RecordComponent component:value.getClass().getRecordComponents()) {
                // This frozen digest covers the pre-D175 shape, as ADDED does for metrics.
                // Legacy calculator entry points must keep the new optional block absent.
                if (value instanceof Review && component.getName().equals("materialTypes")) {
                    org.junit.jupiter.api.Assertions.assertNull(component.getAccessor().invoke(value));
                    continue;
                }
                s.append(component.getName()).append('=').append(canonical(component.getAccessor().invoke(value))).append(';');
            }
            return s.append(')').toString();
        }
        String s=value instanceof BigDecimal number ? number.toPlainString() : value.toString();
        return s.length()+":"+s;
    }
}
