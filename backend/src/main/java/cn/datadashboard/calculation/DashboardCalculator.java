package cn.datadashboard.calculation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static cn.datadashboard.calculation.DashboardModels.*;
import static cn.datadashboard.calculation.MetricArithmetic.Result;
import static cn.datadashboard.calculation.MetricArithmetic.Status;

/** In-memory implementation of dashboard-query-v1; no storage or collection side effects. */
public final class DashboardCalculator {
    private static final String SCHEMA = "dashboard-query-1.7.0";
    private static final List<String> GROUP_KEYS = List.of("overall", "new", "historical", "aigc");
    private static final List<String> METRIC_KEYS =
            List.of("spend", "materialCount", "ctr", "cvr", "spendShare", "roi", "cpm", "cpc");
    private final Clock clock;

    public DashboardCalculator() {
        this(Clock.systemUTC());
    }

    public DashboardCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Explicit immutable projection; the owner controls its bounded lifetime and source validation. */
    public static PreparedSnapshot prepare(Snapshot snapshot) {
        return new PreparedSnapshot(snapshot);
    }

    public Review review(Snapshot snapshot, String projectId, DateRange current, DateRange previous, int limit) {
        validateProject(projectId);
        validateRange(current);
        validateRange(previous);
        validateLimit(limit);
        return reviewPrepared(prepare(snapshot), projectId, current, previous, limit);
    }

    public Review reviewPrepared(PreparedSnapshot snapshot, String projectId, DateRange current, DateRange previous, int limit) {
        validateProject(projectId);
        validateRange(current);
        validateRange(previous);
        validateLimit(limit);
        Index index = new Index(Objects.requireNonNull(snapshot, "prepared snapshot"));
        PeriodCalculation now = period(index, "CURRENT", "本周期", current);
        PeriodCalculation before = period(index, "PREVIOUS", "上周期", previous);
        List<Group> comparisons = new ArrayList<>();
        for (int group = 0; group < GROUP_KEYS.size(); group++) {
            Map<String, Result> metrics = new LinkedHashMap<>();
            for (String metric : METRIC_KEYS) {
                Result a = now.period.groups().get(group).metrics().get(metric);
                Result b = before.period.groups().get(group).metrics().get(metric);
                metrics.put(metric, inherited(MetricArithmetic.comparisonIndex(a.value(), b.value()), a, b));
            }
            comparisons.add(new Group(GROUP_KEYS.get(group), Map.copyOf(metrics)));
        }

        List<ReviewMaterialRow> topMaterials = detailedRows(index, now, before, null, limit);
        List<MaterialRow> newMaterials = materialRows(index, now, before, 10, true);
        String state;
        if (now.period.coverage().coveredDays() == 0 && before.period.coverage().coveredDays() == 0) {
            state = "UNAVAILABLE";
        } else if (isPartial(now) || isPartial(before)) {
            state = "PARTIAL";
        } else {
            state = "READY";
        }
        return new Review(SCHEMA, projectId, "CURRENT_FACTS", clock.instant().toString(), limit,
                List.of(now.period, before.period), state, List.copyOf(comparisons), newMaterials,
                topMaterials, index.fingerprint(), Map.of(
                        "new", cohortRows(index, now, before, "new"),
                        "historical", cohortRows(index, now, before, "historical"),
                        "aigc", cohortRows(index, now, before, "aigc")));
    }

    public Top top(Snapshot snapshot, String projectId, LocalDate date, int limit) {
        validateTop(projectId, date, limit);
        return topPrepared(prepare(snapshot), projectId, date, limit);
    }

    /** Additive type analysis; reuse the verified immutable dimensions and bounded material summaries. */
    public Review reviewWithMaterialTypesPrepared(PreparedSnapshot snapshot, String projectId,
                                                  DateRange current, DateRange previous, int limit) {
        return reviewWithMaterialTypesPrepared(snapshot, projectId, current, previous, limit, false);
    }

    public Review reviewWithEnhancedMaterialTypesPrepared(PreparedSnapshot snapshot, String projectId,
                                                  DateRange current, DateRange previous, int limit) {
        return reviewWithMaterialTypesPrepared(snapshot, projectId, current, previous, limit, true);
    }

    private Review reviewWithMaterialTypesPrepared(PreparedSnapshot snapshot, String projectId,
                                                  DateRange current, DateRange previous, int limit, boolean enhanced) {
        Review base = reviewPrepared(snapshot, projectId, current, previous, limit);
        MaterialTypeReview types = materialTypesPrepared(snapshot, projectId, current, current, previous, previous, enhanced);
        return new Review(base.schemaVersion(), base.projectId(), base.evidenceKind(), base.requestedAt(),
                base.topComparisonDisplayLimit(), base.periods(), base.metricsState(), base.comparisons(),
                base.newMaterials(), base.topMaterials(), base.snapshotFingerprint(), base.cohortMaterials(), types);
    }

    /** Independent 05/06 statistics; date filters reuse the existing immutable index. */
    public MaterialTypeReview materialTypesPrepared(PreparedSnapshot snapshot, String projectId,
            DateRange current, DateRange currentTest, DateRange previous, DateRange previousTest) {
        return materialTypesPrepared(snapshot, projectId, current, currentTest, previous, previousTest, true);
    }

    private MaterialTypeReview materialTypesPrepared(PreparedSnapshot snapshot, String projectId,
            DateRange current, DateRange currentTest, DateRange previous, DateRange previousTest, boolean custom) {
        validateProject(projectId);
        validateRange(current); validateRange(currentTest); validateRange(previous); validateRange(previousTest);
        Index index = new Index(snapshot);
        TypeCalculation now = materialTypePeriod(index, "CURRENT", current, currentTest);
        TypeCalculation before = materialTypePeriod(index, "PREVIOUS", previous, previousTest);
        List<Group> comparisons = new ArrayList<>();
        Map<String, List<MaterialTypeRow>> materials = new LinkedHashMap<>();
        for (int group = 0; group < TYPE_KEYS.size(); group++) {
            String key = TYPE_KEYS.get(group);
            Map<String, Result> compared = new LinkedHashMap<>();
            for (String metric : METRIC_KEYS) {
                Result a = now.period.groups().get(group).metrics().get(metric);
                Result b = before.period.groups().get(group).metrics().get(metric);
                compared.put(metric, inherited(MetricArithmetic.comparisonIndex(a.value(), b.value()), a, b));
            }
            comparisons.add(new Group(key, Map.copyOf(compared)));
            List<MaterialTypeRow> rows = new ArrayList<>();
            for (String id : ranked(now.byMaterial)) {
                if (!key.equals(now.types.get(id))) continue;
                Totals totals = now.byMaterial.get(id);
                Totals prior = before.byMaterial.get(id);
                if (prior == null) prior = new Totals(List.of(), before.period.coverage().coveredDays() > 0,
                        !before.period.coverage().state().equals("COMPLETE"));
                rows.add(new MaterialTypeRow(rows.size() + 1, id, index.name(id, current.end()),
                        index.creationDate(id, current.end()), totals.metrics(now.overall),
                        prior.numberField(Field.SPEND), index.dailySpend(id, current)));
            }
            materials.put(key, List.copyOf(rows));
        }
        return new MaterialTypeReview(custom ? "material-type-review-2.0.0" : "material-type-review-1.0.0",
                List.of(now.period, before.period), List.copyOf(comparisons), Map.copyOf(materials),
                custom ? aiReview(index, now, before) : null);
    }

    private static final List<String> AI_KEYS = List.of("ai", "business", "department");
    private static final java.util.regex.Pattern AI_MARKER = java.util.regex.Pattern.compile("AI(?!R)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** AI is an overlapping observation, never a replacement for original/mixed/other. */
    private static AiMaterialReview aiReview(Index index, TypeCalculation now, TypeCalculation before) {
        MaterialTypePeriod current = aiPeriod(index, now), previous = aiPeriod(index, before);
        List<Group> comparisons = new ArrayList<>();
        for (int i = 0; i < AI_KEYS.size(); i++) {
            Map<String, Result> compared = new LinkedHashMap<>();
            for (String key : METRIC_KEYS) {
                Result a = current.groups().get(i).metrics().get(key), b = previous.groups().get(i).metrics().get(key);
                compared.put(key, inherited(MetricArithmetic.comparisonIndex(a.value(), b.value()), a, b));
            }
            comparisons.add(new Group(AI_KEYS.get(i), Map.copyOf(compared)));
        }
        Map<String, List<MaterialTypeRow>> materials = new LinkedHashMap<>();
        Totals aiTotal = aiTotals(index, now);
        for (String key : List.of("business", "department")) {
            List<MaterialTypeRow> rows = new ArrayList<>();
            for (String id : ranked(now.byMaterial)) {
                String name = index.name(id, now.period.range().end());
                if (!aiMember(name, key)) continue;
                Totals prior = before.byMaterial.get(id);
                if (prior == null) prior = new Totals(List.of(), before.period.coverage().coveredDays() > 0,
                        !before.period.coverage().state().equals("COMPLETE"));
                rows.add(new MaterialTypeRow(rows.size() + 1, id, name,
                        index.creationDate(id, now.period.range().end()), now.byMaterial.get(id).metrics(aiTotal),
                        prior.numberField(Field.SPEND), index.dailySpend(id, now.period.range())));
            }
            materials.put(key, List.copyOf(rows));
        }
        return new AiMaterialReview(List.of(current, previous), List.copyOf(comparisons), Map.copyOf(materials));
    }

    private static boolean aiMember(String name, String key) {
        return name != null && AI_MARKER.matcher(name).find() &&
                (key.equals("ai") || name.contains(key.equals("business") ? "商单" : "部门"));
    }

    private static Totals aiTotals(Index index, TypeCalculation base) {
        Summary summary = new Summary();
        for (String id : base.byMaterial.keySet()) {
            if (aiMember(index.name(id, base.period.range().end()), "ai"))
                summary.merge(index.prepared.series.get(id).aggregate(base.period.range()));
        }
        return new Totals(summary, base.period.coverage().coveredDays() > 0,
                !base.period.coverage().state().equals("COMPLETE"));
    }

    private static MaterialTypePeriod aiPeriod(Index index, TypeCalculation base) {
        DateRange range = base.period.range(); Coverage coverage = base.period.coverage();
        Map<String, Summary> sums = new LinkedHashMap<>(); AI_KEYS.forEach(key -> sums.put(key, new Summary()));
        for (String id : base.byMaterial.keySet()) {
            String name = index.name(id, range.end());
            if (!aiMember(name, "ai")) continue;
            Summary summary = index.prepared.series.get(id).aggregate(range);
            sums.get("ai").merge(summary);
            if (name.contains("商单")) sums.get("business").merge(summary);
            if (name.contains("部门")) sums.get("department").merge(summary);
        }
        boolean covered = coverage.coveredDays() > 0, partialDays = !coverage.state().equals("COMPLETE");
        Totals overall = new Totals(sums.get("ai"), covered, partialDays);
        boolean unknownName = base.period.groups().stream().anyMatch(group ->
                group.metrics().values().stream().anyMatch(value -> value.partialReasons().contains("MISSING_MATERIAL_NAME")));
        List<Group> groups = new ArrayList<>();
        for (String key : AI_KEYS) {
            Map<String, Result> metrics = new LinkedHashMap<>(new Totals(sums.get(key), covered, partialDays).metrics(overall));
            if (unknownName) metrics.replaceAll((metric, value) -> partial(value, "MISSING_MATERIAL_NAME"));
            if (base.period.unresolvedDateMaterials() > 0) metrics.replaceAll((metric, value) -> partial(value, "COHORT_UNASSIGNED"));
            groups.add(new Group(key, Map.copyOf(metrics)));
        }
        return new MaterialTypePeriod(base.period.key(), range, coverage, List.copyOf(groups),
                base.period.excludedUndatedMaterials(), base.period.unresolvedDateMaterials(), base.period.testRange());
    }

    private static final List<String> TYPE_KEYS = List.of("original", "mixed", "other");
    private record TypeCalculation(MaterialTypePeriod period, Map<String, Totals> byMaterial,
                                   Map<String, String> types, Totals overall) { }

    private static TypeCalculation materialTypePeriod(Index index, String key, DateRange range, DateRange testRange) {
        Coverage coverage = index.coverage(range);
        boolean covered = coverage.coveredDays() > 0, partialDays = !coverage.state().equals("COMPLETE");
        // Range-index lookup only. No scan of all historical rows to rediscover names/first dates.
        Set<String> candidates = new HashSet<>();
        for (Fact fact : index.inRange(range)) candidates.add(fact.materialId());
        Map<String, Summary> summaries = new LinkedHashMap<>();
        TYPE_KEYS.forEach(type -> summaries.put(type, new Summary()));
        Summary all = new Summary();
        Map<String, Totals> totals = new HashMap<>();
        Map<String, String> types = new HashMap<>();
        int undated = 0, unresolved = 0;
        boolean unknownName = false;
        for (String id : candidates) {
            CreationDate creation = index.creationDate(id, range.end());
            if (creation.status().equals("VERIFIED") &&
                    (creation.date().isBefore(testRange.start()) || creation.date().isAfter(testRange.end()))) continue;
            Summary summary = index.prepared.series.get(id).aggregate(range);
            if (summary.eligibleCount == 0 && !summary.unknownSpend) continue;
            if (creation.status().equals("NOT_APPLICABLE")) { undated++; continue; }
            if (!creation.status().equals("VERIFIED")) { unresolved++; continue; }
            String name = index.name(id, range.end());
            String type = name != null && name.contains("原创") ? "original"
                    : name != null && name.contains("混剪") ? "mixed" : "other";
            unknownName |= name == null;
            summaries.get(type).merge(summary);
            all.merge(summary);
            types.put(id, type);
            totals.put(id, new Totals(summary, covered, partialDays));
        }
        Totals overall = new Totals(all, covered, partialDays);
        List<Group> groups = new ArrayList<>();
        for (String type : TYPE_KEYS) {
            Totals total = new Totals(summaries.get(type), covered, partialDays);
            Map<String, Result> metrics = new LinkedHashMap<>(total.metrics(overall));
            if (unknownName) metrics.replaceAll((metric, value) -> partial(value, "MISSING_MATERIAL_NAME"));
            if (unresolved > 0) metrics.replaceAll((metric, value) -> partial(value, "COHORT_UNASSIGNED"));
            groups.add(new Group(type, Map.copyOf(metrics)));
        }
        return new TypeCalculation(new MaterialTypePeriod(key, range, coverage, List.copyOf(groups), undated, unresolved, range.equals(testRange) ? null : testRange),
                Map.copyOf(totals), Map.copyOf(types), overall);
    }

    private static void validateTop(String projectId, LocalDate date, int limit) {
        validateProject(projectId);
        validateLimit(limit);
        if (date == null) {
            throw new IllegalArgumentException("A selected date is required");
        }
        try { date.minusDays(6); }
        catch (DateTimeException exception) {
            throw new IllegalArgumentException("Selected date cannot provide the required window", exception);
        }
    }

    public Top topPrepared(PreparedSnapshot snapshot, String projectId, LocalDate date, int limit) {
        validateTop(projectId, date, limit);
        DateRange week;
        DateRange recent;
        try {
            week = new DateRange(date.minusDays(6), date);
            recent = new DateRange(date.minusDays(2), date);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Selected date cannot provide the required window", exception);
        }
        DateRange year = new DateRange(date.withDayOfYear(1), date);
        Index index = new Index(Objects.requireNonNull(snapshot, "prepared snapshot"));
        Coverage weekCoverage = index.coverage(week);
        Coverage recentCoverage = index.coverage(recent);
        Coverage annualCoverage = index.coverage(year);
        List<TopDay> days = new ArrayList<>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate day = date.minusDays(offset);
            days.add(new TopDay(day, index.days.contains(day) ? "AVAILABLE" : "MISSING"));
        }

        List<Fact> selectedRows = index.inRange(new DateRange(date, date));
        String rankingState = !index.days.contains(date) ? "UNAVAILABLE"
                : selectedRows.stream().anyMatch(row -> row.spend() == null) ? "PARTIAL" : "READY";
        List<TopRow> rows = new ArrayList<>();
        if (!rankingState.equals("UNAVAILABLE")) {
            Map<String, Totals> dayTotals = materialTotals(selectedRows, true);
            List<String> ranked = ranked(dayTotals);
            for (String id : ranked.subList(0, Math.min(limit, ranked.size()))) {
                List<DailySpend> dailySpend = new ArrayList<>();
                for (TopDay day : days) {
                    dailySpend.add(new DailySpend(day.date(), index.materialSpend(id, day.date())));
                }
                Totals annual = index.materialTotals(id, year);
                Totals weekly = index.materialTotals(id, week);
                Totals threeDays = index.materialTotals(id, recent);
                Totals selected = dayTotals.get(id);
                FirstSpend firstSpend = index.firstSpend(id, date);
                CreationDate creation = index.creationDate(id, date);
                DateRange lifetimeRange = creation.status().equals("VERIFIED")
                        ? new DateRange(creation.date(), date) : null;
                if (creation.status().equals("NOT_APPLICABLE")) {
                    List<Fact> material = index.byMaterial.getOrDefault(id, List.of());
                    LocalDate observed = material.isEmpty() || material.getFirst().date().isAfter(date)
                            ? null : material.getFirst().date();
                    if (observed != null) lifetimeRange = new DateRange(observed, date);
                }
                LocalDate firstAvailable = index.days.isEmpty() ? date : index.days.first();
                Totals lifetime = index.materialTotals(id, lifetimeRange == null
                        ? new DateRange(firstAvailable.isAfter(date) ? date : firstAvailable, date) : lifetimeRange);
                Result lifetimeSpend = lifetime.numberField(Field.SPEND);
                Result lifetimeRoi = lifetime.ratio(Field.GMV, Field.SPEND, false);
                if (lifetimeRange == null) {
                    lifetimeSpend = partial(lifetimeSpend, "UNKNOWN_CREATION_DATE");
                    lifetimeRoi = partial(lifetimeRoi, "UNKNOWN_CREATION_DATE");
                }
                rows.add(new TopRow(rows.size() + 1, id, index.name(id, date), List.copyOf(dailySpend),
                        TopThreeDayTrend.evaluate(dailySpend.get(0).spend().value(),
                                dailySpend.get(1).spend().value(), dailySpend.get(2).spend().value()),
                        annual.ratio(Field.GMV, Field.SPEND, false),
                        weekly.ratio(Field.GMV, Field.SPEND, false),
                        selected.ratio(Field.GMV, Field.SPEND, false), firstSpend,
                        threeDays.numberField(Field.ORDERS), threeDays.numberField(Field.SPEND),
                        threeDays.ratio(Field.SPEND, Field.CLICKS, false),
                        threeDays.ratio(Field.SPEND, Field.IMPRESSIONS, true),
                        threeDays.ratio(Field.GMV, Field.SPEND, false),
                        lifetimeSpend, lifetimeRoi,
                        lifetimeRange == null ? null : index.coverage(lifetimeRange), creation,
                        weekly.numberField(Field.SPEND),
                        threeDays.numberField(Field.IMPRESSIONS), threeDays.numberField(Field.CLICKS),
                        threeDays.ratio(Field.CLICKS, Field.IMPRESSIONS, false),
                        threeDays.ratio(Field.ORDERS, Field.CLICKS, false)));
            }
        }
        return new Top(SCHEMA, projectId, "CURRENT_FACTS", clock.instant().toString(), date, limit,
                weekCoverage, List.copyOf(days), rankingState, recentCoverage, annualCoverage,
                List.copyOf(rows), index.fingerprint());
    }

    private static PeriodCalculation period(Index index, String key, String label, DateRange range) {
        Coverage coverage = index.coverage(range);
        List<Fact> rows = index.inRange(range);
        Totals overall = new Totals(rows, coverage.coveredDays() > 0, !coverage.state().equals("COMPLETE"));
        Map<String, String> cohorts = new HashMap<>();
        Set<String> pending = new HashSet<>();
        for (Fact row : rows) {
            if (positive(row.spend())) {
                String cohort = cohorts.computeIfAbsent(row.materialId(), id -> index.cohort(id, range));
                if (cohort.equals("pending")) {
                    pending.add(row.materialId());
                }
            }
        }
        List<Group> groups = new ArrayList<>();
        groups.add(new Group("overall", overall.metrics(overall)));
        for (String cohort : List.of("new", "historical", "aigc")) {
            List<Fact> assigned = rows.stream().filter(row -> cohort.equals(cohorts.get(row.materialId()))).toList();
            Totals totals = new Totals(assigned, coverage.coveredDays() > 0
                    && (!assigned.isEmpty() || (pending.isEmpty() && !overall.unknownSpend)),
                    !coverage.state().equals("COMPLETE"));
            Map<String, Result> groupMetrics = new LinkedHashMap<>(totals.metrics(overall));
            if (overall.unknownSpend) groupMetrics.replaceAll((metric, value) -> partial(value, "UNKNOWN_SPEND"));
            if (!cohort.equals("aigc") && !pending.isEmpty()) groupMetrics.replaceAll((metric, value) -> partial(value, "COHORT_UNASSIGNED"));
            groups.add(new Group(cohort, Map.copyOf(groupMetrics)));
        }
        Period period = new Period(key, label, range, coverage, List.copyOf(groups), pending.size());
        return new PeriodCalculation(period, overall, materialTotals(rows, coverage.coveredDays() > 0,
                !coverage.state().equals("COMPLETE")), cohorts);
    }

    private static boolean isPartial(PeriodCalculation calculation) {
        return !calculation.period.coverage().state().equals("COMPLETE")
                || calculation.period.pendingMaterialCount() > 0
                || calculation.period.groups().stream().flatMap(group -> group.metrics().values().stream())
                .anyMatch(value -> value.status() == Status.UNKNOWN_INPUT || value.partial());
    }

    private static List<MaterialRow> materialRows(Index index, PeriodCalculation now,
                                                 PeriodCalculation before, int limit, boolean newOnly) {
        if (now.period.coverage().coveredDays() == 0) {
            return List.of();
        }
        List<MaterialRow> result = new ArrayList<>();
        for (String id : ranked(now.byMaterial)) {
            if (newOnly && !"new".equals(now.cohorts.get(id))) {
                continue;
            }
            Totals current = now.byMaterial.get(id);
            Totals previous = before.byMaterial.get(id);
            if (previous == null) {
                previous = new Totals(List.of(), before.period.coverage().coveredDays() > 0,
                        !before.period.coverage().state().equals("COMPLETE"));
            }
            result.add(new MaterialRow(result.size() + 1, id, index.name(id, now.period.range().end()),
                    current.numberField(Field.SPEND), previous.numberField(Field.SPEND),
                    inherited(MetricArithmetic.periodChange(current.spend, previous.spend), current.numberField(Field.SPEND), previous.numberField(Field.SPEND)),
                    index.firstSpend(id, now.period.range().end()),
                    inherited(MetricArithmetic.comparisonIndex(current.spend, now.overall.spend), current.numberField(Field.SPEND), now.overall.numberField(Field.SPEND)),
                    current.ratio(Field.GMV, Field.SPEND, false), index.creationDate(id, now.period.range().end())));
            if (result.size() == limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static List<ReviewMaterialRow> cohortRows(Index index, PeriodCalculation now,
                                                     PeriodCalculation before, String cohort) {
        return detailedRows(index, now, before, cohort, Integer.MAX_VALUE);
    }

    private static List<ReviewMaterialRow> detailedRows(Index index, PeriodCalculation now,
                                                       PeriodCalculation before, String cohort, int limit) {
        if (now.period.coverage().coveredDays() == 0) {
            return List.of();
        }
        List<ReviewMaterialRow> result = new ArrayList<>();
        for (String id : ranked(now.byMaterial)) {
            if (cohort != null && !cohort.equals(now.cohorts.get(id))) continue;
            Totals current = now.byMaterial.get(id);
            Totals previous = before.byMaterial.get(id);
            if (previous == null) previous = new Totals(List.of(), before.period.coverage().coveredDays() > 0,
                    !before.period.coverage().state().equals("COMPLETE"));
            result.add(new ReviewMaterialRow(result.size() + 1, id, index.name(id, now.period.range().end()),
                    current.numberField(Field.SPEND), previous.numberField(Field.SPEND),
                    inherited(MetricArithmetic.periodChange(current.spend, previous.spend), current.numberField(Field.SPEND), previous.numberField(Field.SPEND)),
                    index.firstSpend(id, now.period.range().end()),
                    inherited(MetricArithmetic.comparisonIndex(current.spend, now.overall.spend), current.numberField(Field.SPEND), now.overall.numberField(Field.SPEND)),
                    current.ratio(Field.GMV, Field.SPEND, false),
                    current.ratio(Field.CLICKS, Field.IMPRESSIONS, false),
                    current.ratio(Field.ORDERS, Field.CLICKS, false),
                    current.ratio(Field.SPEND, Field.IMPRESSIONS, true),
                    index.dailySpend(id, now.period.range()), index.creationDate(id, now.period.range().end())));
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    private static Map<String, Totals> materialTotals(List<Fact> rows, boolean covered) {
        return materialTotals(rows, covered, false);
    }

    private static Map<String, Totals> materialTotals(List<Fact> rows, boolean covered, boolean partialDays) {
        Map<String, List<Fact>> grouped = new HashMap<>();
        for (Fact row : rows) {
            grouped.computeIfAbsent(row.materialId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, Totals> result = new HashMap<>();
        grouped.forEach((id, facts) -> result.put(id, new Totals(facts, covered, partialDays)));
        return result;
    }

    private static List<String> ranked(Map<String, Totals> totals) {
        return totals.keySet().stream().filter(id -> positive(totals.get(id).spend))
                .sorted(Comparator.<String, BigDecimal>comparing(id -> totals.get(id).spend).reversed()
                        .thenComparing(Comparator.naturalOrder())).toList();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static Result number(BigDecimal value) {
        return new Result(value == null ? Status.UNKNOWN_INPUT : Status.VALID, value);
    }

    private static Result partial(Result result, String reason) {
        Set<String> reasons = new java.util.LinkedHashSet<>(result.partialReasons());
        reasons.add(reason);
        return new Result(result.status(), result.value(), true, List.copyOf(reasons));
    }

    private static Result inherited(Result result, Result... inputs) {
        for (Result input : inputs) for (String reason : input.partialReasons()) result = partial(result, reason);
        return result;
    }

    /** D-073: business fact dates, never insertion times; AIGC has no creation-date meaning. */
    public static CreationDate resolveCreationDate(List<Fact> facts, LocalDate anchor) {
        LocalDate earliest = null;
        for (Fact fact : facts) {
            if (fact.date().isAfter(anchor)) continue;
            if (fact.materialId().equals("-2")) return new CreationDate("NOT_APPLICABLE", null, null);
            if (earliest == null || fact.date().isBefore(earliest)) earliest = fact.date();
            if (fact.createdDate() != null && fact.createdDate().isBefore(earliest)) earliest = fact.createdDate();
        }
        return earliest == null ? new CreationDate("PENDING", null, "MISSING_CREATION_DATE")
                : new CreationDate("VERIFIED", earliest, null);
    }

    /** Source-only anomalies retained for maintenance observation, not D-073 output eligibility. */
    public static CreationDate inspectSourceCreationDate(List<Fact> facts, LocalDate anchor) {
        Set<LocalDate> dates = new HashSet<>();
        LocalDate earliestObservation = null;
        for (Fact fact : facts) {
            if (fact.date().isAfter(anchor)) continue;
            if (earliestObservation == null || fact.date().isBefore(earliestObservation)) earliestObservation = fact.date();
            if (fact.createdDate() != null) dates.add(fact.createdDate());
        }
        if (dates.isEmpty()) return new CreationDate("PENDING", null, "MISSING_CREATION_DATE");
        if (dates.size() > 1) return new CreationDate("PENDING", null, "CONFLICTING_CREATION_DATE");
        LocalDate date = dates.iterator().next();
        if (earliestObservation != null && date.isAfter(earliestObservation))
            return new CreationDate("PENDING", null, "CREATION_AFTER_OBSERVATION");
        return new CreationDate("VERIFIED", date, null);
    }

    private static void validateProject(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("A project ID is required");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Display limit must be between 1 and 200");
        }
    }

    private static void validateRange(DateRange range) {
        if (range == null || range.start() == null || range.end() == null || range.start().isAfter(range.end())
                || ChronoUnit.DAYS.between(range.start(), range.end()) >= 3660) {
            throw new IllegalArgumentException("Date range must be ordered and contain 1 to 3660 days");
        }
    }

    private record PeriodCalculation(Period period, Totals overall, Map<String, Totals> byMaterial,
                                     Map<String, String> cohorts) { }

    private enum Field { SPEND, GMV, IMPRESSIONS, CLICKS, ORDERS }

    /** Curves need only spend, not five ratio populations or a material-count set. */
    private static Result spendOnly(List<Fact> rows, boolean covered, boolean partialDays) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean unknown = false, eligible = false;
        for (Fact row : rows) {
            if (row.spend() == null) unknown = true;
            else if (positive(row.spend())) { sum = sum.add(row.spend()); eligible = true; }
        }
        Result result = number(!covered || (!eligible && unknown) ? null : sum);
        if (partialDays) result = partial(result, "MISSING_ACCOUNT_DAYS");
        if (unknown) result = partial(result, "UNKNOWN_SPEND");
        return result;
    }

    /** Mutable only while a projection/request is built; published monthly summaries are never modified. */
    private static final class Summary {
        private static final int[][] PAIRS = {{1, 0}, {3, 2}, {4, 3}, {0, 2}, {0, 3}};
        private final BigDecimal[] sums = zeroes(5);
        private final int[] known = new int[5];
        private final BigDecimal[] pairNumerators = zeroes(5);
        private final BigDecimal[] pairDenominators = zeroes(5);
        private final int[] paired = new int[5];
        private final Set<String> included = new HashSet<>();
        private final Set<String> uncertain = new HashSet<>();
        private boolean unknownSpend;
        private int eligibleCount;

        private static Summary from(List<Fact> rows) {
            Summary result = new Summary();
            for (Fact row : rows) result.add(row);
            return result;
        }

        private void add(Fact row) {
            if (row.spend() == null) unknownSpend = true;
            if (!positive(row.spend())) return;
            eligibleCount++;
            BigDecimal[] values = {row.spend(), row.gmv(), row.impressions(), row.clicks(), row.orders()};
            for (int f = 0; f < values.length; f++) {
                if (values[f] != null) {
                    known[f]++;
                    sums[f] = sums[f].add(values[f]);
                }
            }
            for (int p = 0; p < PAIRS.length; p++) {
                BigDecimal a = values[PAIRS[p][0]], b = values[PAIRS[p][1]];
                if (a != null && b != null) {
                    paired[p]++;
                    pairNumerators[p] = pairNumerators[p].add(a);
                    pairDenominators[p] = pairDenominators[p].add(b);
                }
            }
            if (row.materialName() == null) uncertain.add(row.materialId());
            else if (!row.materialName().toUpperCase(Locale.ROOT).contains("ZZ")) included.add(row.materialId());
        }

        private void merge(Summary other) {
            unknownSpend |= other.unknownSpend;
            eligibleCount += other.eligibleCount;
            included.addAll(other.included);
            uncertain.addAll(other.uncertain);
            for (int i = 0; i < 5; i++) {
                sums[i] = sums[i].add(other.sums[i]);
                known[i] += other.known[i];
                pairNumerators[i] = pairNumerators[i].add(other.pairNumerators[i]);
                pairDenominators[i] = pairDenominators[i].add(other.pairDenominators[i]);
                paired[i] += other.paired[i];
            }
        }

        private static BigDecimal[] zeroes(int size) {
            BigDecimal[] result = new BigDecimal[size];
            java.util.Arrays.fill(result, BigDecimal.ZERO);
            return result;
        }
    }

    /** Request-local result wrapper; ratio populations are never averaged across months. */
    private static final class Totals {
        private final BigDecimal[] sums;
        private final int[] known;
        private final BigDecimal[] pairNumerators;
        private final BigDecimal[] pairDenominators;
        private final int[] paired;
        private final BigDecimal spend;
        private final BigDecimal materialCount;
        private final boolean unknownSpend;
        private final boolean covered;
        private final boolean partialDays;
        private final int eligibleCount;
        private final boolean uncertainName;

        private Totals(List<Fact> rows, boolean covered) {
            this(rows, covered, false);
        }

        private Totals(List<Fact> rows, boolean covered, boolean partialDays) {
            this(Summary.from(rows), covered, partialDays);
        }

        private Totals(Summary summary, boolean covered, boolean partialDays) {
            this.covered = covered;
            this.partialDays = partialDays;
            sums = summary.sums;
            known = summary.known;
            pairNumerators = summary.pairNumerators;
            pairDenominators = summary.pairDenominators;
            paired = summary.paired;
            unknownSpend = summary.unknownSpend;
            eligibleCount = summary.eligibleCount;
            spend = numberField(Field.SPEND).value();
            uncertainName = !summary.included.containsAll(summary.uncertain);
            materialCount = covered && (!summary.included.isEmpty() || (!unknownSpend && !uncertainName))
                    ? BigDecimal.valueOf(summary.included.size()) : null;
        }

        private Result annotate(Result value) {
            if (partialDays) value = partial(value, "MISSING_ACCOUNT_DAYS");
            if (unknownSpend) value = partial(value, "UNKNOWN_SPEND");
            return value;
        }

        private Result numberField(Field field) {
            int f = field.ordinal();
            Result result = number(!covered || (known[f] == 0 && (eligibleCount > 0 || unknownSpend))
                    ? null : sums[f]);
            if (known[f] != eligibleCount) result = partial(result, "MISSING_FIELD");
            return annotate(result);
        }

        private Result ratio(Field numerator, Field denominator, boolean thousand) {
            int p = 0;
            while (p < Summary.PAIRS.length && (Summary.PAIRS[p][0] != numerator.ordinal() || Summary.PAIRS[p][1] != denominator.ordinal())) p++;
            if (p == Summary.PAIRS.length) throw new IllegalArgumentException("UNSUPPORTED_RATIO_PAIR");
            boolean unknown = !covered || (paired[p] == 0 && (eligibleCount > 0 || unknownSpend));
            BigDecimal a = unknown ? null : pairNumerators[p];
            BigDecimal b = unknown ? null : pairDenominators[p];
            Result result = thousand ? MetricArithmetic.cpm(a, b) : MetricArithmetic.comparisonIndex(a, b);
            if (paired[p] != eligibleCount) result = partial(result, "MISSING_FIELD");
            return annotate(result);
        }

        private Map<String, Result> metrics(Totals overall) {
            Map<String, Result> metrics = new LinkedHashMap<>();
            metrics.put("spend", numberField(Field.SPEND));
            metrics.put("gmv", numberField(Field.GMV));
            metrics.put("impressions", numberField(Field.IMPRESSIONS));
            metrics.put("clicks", numberField(Field.CLICKS));
            Result count = annotate(number(materialCount));
            if (uncertainName) count = partial(count, "MISSING_MATERIAL_NAME");
            metrics.put("materialCount", count);
            metrics.put("ctr", ratio(Field.CLICKS, Field.IMPRESSIONS, false));
            metrics.put("cvr", ratio(Field.ORDERS, Field.CLICKS, false));
            metrics.put("spendShare", inherited(MetricArithmetic.comparisonIndex(spend, overall.spend),
                    numberField(Field.SPEND), overall.numberField(Field.SPEND)));
            metrics.put("roi", ratio(Field.GMV, Field.SPEND, false));
            metrics.put("cpm", ratio(Field.SPEND, Field.IMPRESSIONS, true));
            metrics.put("cpc", ratio(Field.SPEND, Field.CLICKS, false));
            return Map.copyOf(metrics);
        }
    }

    public record PreparationStats(int factRows, int materials, int monthlySummaries, int dimensionPoints) { }

    /**
     * Fully owned immutable lists and private read-only indexes. Not an authorization or hash-verification
     * boundary: callers must bind this object to their verified source snapshot, never just its fingerprint.
     */
    public static final class PreparedSnapshot {
        private final Snapshot snapshot;
        private final NavigableSet<LocalDate> days;
        private final NavigableMap<LocalDate, List<Fact>> byDate;
        private final Map<String, List<Fact>> byMaterial;
        private final Map<String, MaterialSeries> series;
        private final String fingerprint;
        private final PreparationStats stats;

        private PreparedSnapshot(Snapshot source) {
            if (source == null || source.accountId() == null || source.accountId().isBlank()
                    || source.days() == null || source.facts() == null) {
                throw new IllegalArgumentException("A validated account snapshot is required");
            }
            // Facts and evidence are immutable records; never retain caller-owned mutable lists.
            List<DayEvidence> evidence = new ArrayList<>(source.days());
            List<Fact> inputFacts = new ArrayList<>(source.facts());
            TreeSet<LocalDate> validDays = new TreeSet<>();
            TreeMap<LocalDate, List<Fact>> dates = new TreeMap<>();
            Map<String, List<Fact>> materials = new HashMap<>();
            for (DayEvidence day : evidence) {
                if (day == null || day.date() == null || day.contentHash() == null || !validDays.add(day.date()))
                    throw new IllegalArgumentException("Snapshot needs one evidence version per date");
            }
            for (Fact fact : inputFacts) {
                if (fact == null || fact.date() == null || fact.materialId() == null || fact.materialId().isBlank()
                        || !validDays.contains(fact.date()))
                    throw new IllegalArgumentException("Facts must belong to a complete snapshot day");
                dates.computeIfAbsent(fact.date(), ignored -> new ArrayList<>()).add(fact);
                materials.computeIfAbsent(fact.materialId(), ignored -> new ArrayList<>()).add(fact);
            }
            snapshot = new Snapshot(source.accountId(), List.copyOf(evidence), List.copyOf(inputFacts));
            dates.replaceAll((date, facts) -> List.copyOf(facts));
            materials.replaceAll((id, facts) -> {
                facts.sort(Comparator.comparing(Fact::date));
                return List.copyOf(facts);
            });
            days = Collections.unmodifiableNavigableSet(validDays);
            byDate = Collections.unmodifiableNavigableMap(dates);
            byMaterial = Map.copyOf(materials);
            List<LocalDate> orderedDays = List.copyOf(validDays);
            Map<String, MaterialSeries> built = new HashMap<>();
            int months = 0, dimensions = 0;
            for (var entry : materials.entrySet()) {
                MaterialSeries value = new MaterialSeries(entry.getValue(), orderedDays);
                built.put(entry.getKey(), value);
                months += value.months.size();
                dimensions += value.dimensions.size();
            }
            series = Map.copyOf(built);
            fingerprint = DashboardCalculator.fingerprint(snapshot);
            stats = new PreparationStats(snapshot.facts().size(), materials.size(), months, dimensions);
        }

        public Snapshot snapshot() { return snapshot; }
        public String accountId() { return snapshot.accountId(); }
        public String fingerprint() { return fingerprint; }
        public PreparationStats stats() { return stats; }
    }

    private record DimensionPoint(LocalDate observed, String name, CreationDate creation, FirstSpend firstSpend) { }

    private static final class MaterialSeries {
        private final List<Fact> facts;
        private final NavigableMap<YearMonth, Summary> months;
        private final List<DimensionPoint> dimensions;

        private MaterialSeries(List<Fact> facts, List<LocalDate> accountDays) {
            this.facts = facts;
            TreeMap<YearMonth, Summary> monthly = new TreeMap<>();
            for (Fact fact : facts) monthly.computeIfAbsent(YearMonth.from(fact.date()), ignored -> new Summary()).add(fact);
            months = Collections.unmodifiableNavigableMap(monthly);
            List<DimensionPoint> points = new ArrayList<>();
            String name = null;
            LocalDate earliestCreation = null, firstPositive = null, singleCreated = null;
            boolean conflictingCreated = false, unknownSeen = false, unknownThroughFirst = false;
            for (int start = 0; start < facts.size();) {
                LocalDate date = facts.get(start).date();
                int end = start;
                while (end < facts.size() && facts.get(end).date().equals(date)) {
                    Fact fact = facts.get(end++);
                    if (fact.materialName() != null) name = fact.materialName();
                    if (earliestCreation == null || date.isBefore(earliestCreation)) earliestCreation = date;
                    if (fact.createdDate() != null) {
                        if (fact.createdDate().isBefore(earliestCreation)) earliestCreation = fact.createdDate();
                        if (singleCreated == null) singleCreated = fact.createdDate();
                        else if (!singleCreated.equals(fact.createdDate())) conflictingCreated = true;
                    }
                    if (fact.spend() == null) unknownSeen = true;
                    if (firstPositive == null && positive(fact.spend())) firstPositive = date;
                }
                // Includes every row on the first positive day, including any following unknown row.
                if (date.equals(firstPositive)) unknownThroughFirst = unknownSeen;
                boolean verified = false;
                if (firstPositive != null && singleCreated != null && !conflictingCreated
                        && !singleCreated.isAfter(firstPositive) && !unknownThroughFirst) {
                    long expected = ChronoUnit.DAYS.between(singleCreated, firstPositive) + 1;
                    verified = dateBound(accountDays, firstPositive, true) - dateBound(accountDays, singleCreated, false) == expected;
                }
                CreationDate creation = facts.get(start).materialId().equals("-2")
                        ? new CreationDate("NOT_APPLICABLE", null, null)
                        : new CreationDate("VERIFIED", earliestCreation, null);
                FirstSpend first = new FirstSpend(verified ? "VERIFIED" : "PENDING", verified ? firstPositive : null, firstPositive);
                DimensionPoint point = new DimensionPoint(date, name, creation, first);
                DimensionPoint prior = points.isEmpty() ? null : points.getLast();
                if (prior == null || !Objects.equals(prior.name(), name) || !prior.creation().equals(creation)
                        || !prior.firstSpend().equals(first)) points.add(point);
                start = end;
            }
            dimensions = List.copyOf(points);
        }

        private DimensionPoint at(LocalDate anchor) {
            int low = 0, high = dimensions.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (!dimensions.get(mid).observed().isAfter(anchor)) low = mid + 1;
                else high = mid;
            }
            return low == 0 ? null : dimensions.get(low - 1);
        }

        private Summary aggregate(DateRange range) {
            Summary result = new Summary();
            for (var entry : months.subMap(YearMonth.from(range.start()), true, YearMonth.from(range.end()), true).entrySet()) {
                YearMonth month = entry.getKey();
                if (!range.start().isAfter(month.atDay(1)) && !range.end().isBefore(month.atEndOfMonth())) {
                    result.merge(entry.getValue());
                } else {
                    LocalDate start = range.start().isAfter(month.atDay(1)) ? range.start() : month.atDay(1);
                    LocalDate end = range.end().isBefore(month.atEndOfMonth()) ? range.end() : month.atEndOfMonth();
                    for (Fact fact : facts.subList(Index.bound(facts, start, false), Index.bound(facts, end, true))) result.add(fact);
                }
            }
            return result;
        }
    }

    private static int dateBound(List<LocalDate> days, LocalDate date, boolean inclusiveEnd) {
        int low = 0, high = days.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            int comparison = days.get(mid).compareTo(date);
            if (comparison < 0 || (inclusiveEnd && comparison == 0)) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static String fingerprint(Snapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((SCHEMA + "\n" + snapshot.accountId() + "\n").getBytes(StandardCharsets.UTF_8));
            snapshot.days().stream().sorted(Comparator.comparing(DayEvidence::date)).forEach(day ->
                    digest.update((day.date() + "\t" + day.versionId() + "\t" + day.contentHash() + "\n")
                            .getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class Index {
        private final PreparedSnapshot prepared;
        private final NavigableSet<LocalDate> days;
        private final NavigableMap<LocalDate, List<Fact>> byDate;
        private final Map<String, List<Fact>> byMaterial;
        private record MaterialRange(String id, DateRange range) { }
        private record MaterialAnchor(String id, LocalDate anchor) { }
        // All caches belong to one verified snapshot/request, never to a mutable account globally.
        private final Map<DateRange, Coverage> coverageCache = new HashMap<>();
        private final Map<MaterialRange, Totals> totalsCache = new HashMap<>();
        private final Map<MaterialRange, List<DailySpend>> curveCache = new HashMap<>();
        private final Map<MaterialAnchor, CreationDate> creationCache = new HashMap<>();
        private final Map<MaterialAnchor, FirstSpend> firstSpendCache = new HashMap<>();
        private final Map<MaterialAnchor, String> nameCache = new HashMap<>();

        private Index(PreparedSnapshot prepared) {
            this.prepared = prepared;
            days = prepared.days;
            byDate = prepared.byDate;
            byMaterial = prepared.byMaterial;
        }

        private List<Fact> inRange(DateRange range) {
            return byDate.subMap(range.start(), true, range.end(), true).values().stream()
                    .flatMap(List::stream).toList();
        }

        private Coverage coverage(DateRange range) {
            return coverageCache.computeIfAbsent(range, this::computeCoverage);
        }

        private Coverage computeCoverage(DateRange range) {
            long expected = ChronoUnit.DAYS.between(range.start(), range.end()) + 1;
            List<LocalDate> missing = new ArrayList<>();
            for (long offset = 0; offset < expected; offset++) {
                LocalDate date = range.start().plusDays(offset);
                if (!days.contains(date)) {
                    missing.add(date);
                }
            }
            return new Coverage(missing.isEmpty() ? "COMPLETE" : "PARTIAL", expected,
                    expected - missing.size(), List.copyOf(missing));
        }

        private Totals materialTotals(String id, DateRange range) {
            return totalsCache.computeIfAbsent(new MaterialRange(id, range), ignored -> {
                Coverage coverage = coverage(range);
                MaterialSeries series = prepared.series.get(id);
                if (series != null && ChronoUnit.DAYS.between(range.start(), range.end()) >= 31) {
                    return new Totals(series.aggregate(range), coverage.coveredDays() > 0,
                            !coverage.state().equals("COMPLETE"));
                }
                return new Totals(materialRange(id, range), coverage.coveredDays() > 0, !coverage.state().equals("COMPLETE"));
            });
        }

        private Result materialSpend(String id, LocalDate day) {
            return spendOnly(materialRange(id, new DateRange(day, day)), days.contains(day), !days.contains(day));
        }

        /** Bounds a sorted material series without scanning unrelated dates. */
        private List<Fact> materialRange(String id, DateRange range) {
            List<Fact> rows = byMaterial.getOrDefault(id, List.of());
            return rows.subList(bound(rows, range.start(), false), bound(rows, range.end(), true));
        }

        private static int bound(List<Fact> rows, LocalDate date, boolean inclusiveEnd) {
            int low = 0, high = rows.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                int comparison = rows.get(mid).date().compareTo(date);
                if (comparison < 0 || (inclusiveEnd && comparison == 0)) low = mid + 1;
                else high = mid;
            }
            return low;
        }

        private List<DailySpend> dailySpend(String id, DateRange range) {
            return curveCache.computeIfAbsent(new MaterialRange(id, range), ignored -> computeDailySpend(id, range));
        }

        private List<DailySpend> computeDailySpend(String id, DateRange range) {
            // Group once; do not rescan a material's entire history for every plotted day.
            Map<LocalDate, List<Fact>> grouped = new HashMap<>();
            for (Fact fact : materialRange(id, range)) {
                grouped.computeIfAbsent(fact.date(), ignored -> new ArrayList<>()).add(fact);
            }
            List<DailySpend> result = new ArrayList<>();
            long count = ChronoUnit.DAYS.between(range.start(), range.end()) + 1;
            for (long offset = 0; offset < count; offset++) {
                LocalDate date = range.start().plusDays(offset);
                result.add(new DailySpend(date, spendOnly(grouped.getOrDefault(date, List.of()), days.contains(date), !days.contains(date))));
            }
            return List.copyOf(result);
        }

        private String name(String id, LocalDate anchor) {
            MaterialAnchor key = new MaterialAnchor(id, anchor);
            if (nameCache.containsKey(key)) return nameCache.get(key);
            DimensionPoint point = dimension(id, anchor);
            String name = point == null ? null : point.name();
            nameCache.put(key, name);
            return name;
        }

        private FirstSpend firstSpend(String id, LocalDate anchor) {
            return firstSpendCache.computeIfAbsent(new MaterialAnchor(id, anchor), ignored -> {
                DimensionPoint point = dimension(id, anchor);
                return point == null ? new FirstSpend("PENDING", null, null) : point.firstSpend();
            });
        }

        private String cohort(String id, DateRange range) {
            if (id.equals("-2")) return "aigc";
            CreationDate creation = creationDate(id, range.end());
            if (!creation.status().equals("VERIFIED")) return "pending";
            return creation.date().isBefore(range.start()) ? "historical" : "new";
        }

        private CreationDate creationDate(String id, LocalDate anchor) {
            return creationCache.computeIfAbsent(new MaterialAnchor(id, anchor), ignored -> {
                DimensionPoint point = dimension(id, anchor);
                return point == null ? new CreationDate("PENDING", null, "MISSING_CREATION_DATE") : point.creation();
            });
        }

        private DimensionPoint dimension(String id, LocalDate anchor) {
            MaterialSeries series = prepared.series.get(id);
            return series == null ? null : series.at(anchor);
        }

        private String fingerprint() { return prepared.fingerprint; }
    }
}
