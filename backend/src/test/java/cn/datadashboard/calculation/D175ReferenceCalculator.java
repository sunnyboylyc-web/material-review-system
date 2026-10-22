package cn.datadashboard.calculation;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static cn.datadashboard.calculation.DashboardModels.*;
import static cn.datadashboard.calculation.MetricArithmetic.Result;
import static cn.datadashboard.calculation.MetricArithmetic.Status;

/** Frozen pre-D180 oracle. Do not optimize this test reference alongside production. */
public final class D175ReferenceCalculator {
    private static final String SCHEMA = "dashboard-query-1.7.0";
    private static final List<String> GROUP_KEYS = List.of("overall", "new", "historical", "aigc");
    private static final List<String> METRIC_KEYS =
            List.of("spend", "materialCount", "ctr", "cvr", "spendShare", "roi", "cpm", "cpc");
    private final Clock clock;

    public D175ReferenceCalculator() {
        this(Clock.systemUTC());
    }

    public D175ReferenceCalculator(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Review review(Snapshot snapshot, String projectId, DateRange current, DateRange previous, int limit) {
        validateProject(projectId);
        validateRange(current);
        validateRange(previous);
        validateLimit(limit);
        Index index = new Index(snapshot);
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
        validateProject(projectId);
        validateLimit(limit);
        if (date == null) {
            throw new IllegalArgumentException("A selected date is required");
        }
        DateRange week;
        DateRange recent;
        try {
            week = new DateRange(date.minusDays(6), date);
            recent = new DateRange(date.minusDays(2), date);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Selected date cannot provide the required window", exception);
        }
        DateRange year = new DateRange(date.withDayOfYear(1), date);
        Index index = new Index(snapshot);
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
                    Totals totals = index.materialTotals(id, new DateRange(day.date(), day.date()));
                    dailySpend.add(new DailySpend(day.date(), totals.numberField(Fact::spend)));
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
                    LocalDate observed = index.byMaterial.getOrDefault(id, List.of()).stream()
                            .map(Fact::date).filter(day -> !day.isAfter(date)).min(LocalDate::compareTo).orElse(null);
                    if (observed != null) lifetimeRange = new DateRange(observed, date);
                }
                LocalDate firstAvailable = index.days.isEmpty() ? date : index.days.first();
                Totals lifetime = index.materialTotals(id, lifetimeRange == null
                        ? new DateRange(firstAvailable.isAfter(date) ? date : firstAvailable, date) : lifetimeRange);
                Result lifetimeSpend = lifetime.numberField(Fact::spend);
                Result lifetimeRoi = lifetime.ratio(Fact::gmv, Fact::spend, false);
                if (lifetimeRange == null) {
                    lifetimeSpend = partial(lifetimeSpend, "UNKNOWN_CREATION_DATE");
                    lifetimeRoi = partial(lifetimeRoi, "UNKNOWN_CREATION_DATE");
                }
                rows.add(new TopRow(rows.size() + 1, id, index.name(id, date), List.copyOf(dailySpend),
                        TopThreeDayTrend.evaluate(dailySpend.get(0).spend().value(),
                                dailySpend.get(1).spend().value(), dailySpend.get(2).spend().value()),
                        annual.ratio(Fact::gmv, Fact::spend, false),
                        weekly.ratio(Fact::gmv, Fact::spend, false),
                        selected.ratio(Fact::gmv, Fact::spend, false), firstSpend,
                        threeDays.numberField(Fact::orders), threeDays.numberField(Fact::spend),
                        threeDays.ratio(Fact::spend, Fact::clicks, false),
                        threeDays.ratio(Fact::spend, Fact::impressions, true),
                        threeDays.ratio(Fact::gmv, Fact::spend, false),
                        lifetimeSpend, lifetimeRoi,
                        lifetimeRange == null ? null : index.coverage(lifetimeRange), creation,
                        weekly.numberField(Fact::spend),
                        threeDays.numberField(Fact::impressions), threeDays.numberField(Fact::clicks),
                        threeDays.ratio(Fact::clicks, Fact::impressions, false),
                        threeDays.ratio(Fact::orders, Fact::clicks, false)));
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
                    current.numberField(Fact::spend), previous.numberField(Fact::spend),
                    inherited(MetricArithmetic.periodChange(current.spend, previous.spend), current.numberField(Fact::spend), previous.numberField(Fact::spend)),
                    index.firstSpend(id, now.period.range().end()),
                    inherited(MetricArithmetic.comparisonIndex(current.spend, now.overall.spend), current.numberField(Fact::spend), now.overall.numberField(Fact::spend)),
                    current.ratio(Fact::gmv, Fact::spend, false), index.creationDate(id, now.period.range().end())));
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
                    current.numberField(Fact::spend), previous.numberField(Fact::spend),
                    inherited(MetricArithmetic.periodChange(current.spend, previous.spend), current.numberField(Fact::spend), previous.numberField(Fact::spend)),
                    index.firstSpend(id, now.period.range().end()),
                    inherited(MetricArithmetic.comparisonIndex(current.spend, now.overall.spend), current.numberField(Fact::spend), now.overall.numberField(Fact::spend)),
                    current.ratio(Fact::gmv, Fact::spend, false),
                    current.ratio(Fact::clicks, Fact::impressions, false),
                    current.ratio(Fact::orders, Fact::clicks, false),
                    current.ratio(Fact::spend, Fact::impressions, true),
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
        List<Fact> visible = facts.stream().filter(fact -> !fact.date().isAfter(anchor)).toList();
        if (visible.stream().anyMatch(fact -> fact.materialId().equals("-2")))
            return new CreationDate("NOT_APPLICABLE", null, null);
        LocalDate earliest = null;
        for (Fact fact : visible) {
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

    private static final class Totals {
        private final BigDecimal spend;
        private final BigDecimal materialCount;
        private final boolean unknownSpend;
        private final boolean covered;
        private final boolean partialDays;
        private final List<Fact> eligible;
        private final boolean uncertainName;

        private Totals(List<Fact> rows, boolean covered) {
            this(rows, covered, false);
        }

        private Totals(List<Fact> rows, boolean covered, boolean partialDays) {
            this.covered = covered;
            this.partialDays = partialDays;
            unknownSpend = rows.stream().anyMatch(row -> row.spend() == null);
            eligible = rows.stream().filter(row -> positive(row.spend())).toList();
            spend = numberField(Fact::spend).value();
            Set<String> included = new HashSet<>();
            Set<String> uncertain = new HashSet<>();
            for (Fact row : eligible) {
                if (row.materialName() == null) {
                    uncertain.add(row.materialId());
                } else if (!row.materialName().toUpperCase(Locale.ROOT).contains("ZZ")) {
                    included.add(row.materialId());
                }
            }
            uncertain.removeAll(included);
            uncertainName = !uncertain.isEmpty();
            materialCount = covered && (!included.isEmpty() || (!unknownSpend && !uncertainName))
                    ? BigDecimal.valueOf(included.size()) : null;
        }

        private Result annotate(Result value) {
            if (partialDays) value = partial(value, "MISSING_ACCOUNT_DAYS");
            if (unknownSpend) value = partial(value, "UNKNOWN_SPEND");
            return value;
        }

        private Result numberField(Function<Fact, BigDecimal> field) {
            List<Fact> known = eligible.stream().filter(row -> field.apply(row) != null).toList();
            Result result = number(!covered || (known.isEmpty() && (!eligible.isEmpty() || unknownSpend))
                    ? null : sum(known, field));
            if (known.size() != eligible.size()) result = partial(result, "MISSING_FIELD");
            return annotate(result);
        }

        private Result ratio(Function<Fact, BigDecimal> numerator, Function<Fact, BigDecimal> denominator,
                             boolean thousand) {
            List<Fact> paired = eligible.stream().filter(row -> numerator.apply(row) != null
                    && denominator.apply(row) != null).toList();
            boolean unknown = !covered || (paired.isEmpty() && (!eligible.isEmpty() || unknownSpend));
            BigDecimal a = unknown ? null : sum(paired, numerator);
            BigDecimal b = unknown ? null : sum(paired, denominator);
            Result result = thousand ? MetricArithmetic.cpm(a, b) : MetricArithmetic.comparisonIndex(a, b);
            if (paired.size() != eligible.size()) result = partial(result, "MISSING_FIELD");
            return annotate(result);
        }

        private Map<String, Result> metrics(Totals overall) {
            Map<String, Result> metrics = new LinkedHashMap<>();
            metrics.put("spend", numberField(Fact::spend));
            metrics.put("gmv", numberField(Fact::gmv));
            metrics.put("impressions", numberField(Fact::impressions));
            metrics.put("clicks", numberField(Fact::clicks));
            Result count = annotate(number(materialCount));
            if (uncertainName) count = partial(count, "MISSING_MATERIAL_NAME");
            metrics.put("materialCount", count);
            metrics.put("ctr", ratio(Fact::clicks, Fact::impressions, false));
            metrics.put("cvr", ratio(Fact::orders, Fact::clicks, false));
            metrics.put("spendShare", inherited(MetricArithmetic.comparisonIndex(spend, overall.spend),
                    numberField(Fact::spend), overall.numberField(Fact::spend)));
            metrics.put("roi", ratio(Fact::gmv, Fact::spend, false));
            metrics.put("cpm", ratio(Fact::spend, Fact::impressions, true));
            metrics.put("cpc", ratio(Fact::spend, Fact::clicks, false));
            return Map.copyOf(metrics);
        }

        private static BigDecimal sum(List<Fact> facts, Function<Fact, BigDecimal> field) {
            BigDecimal total = BigDecimal.ZERO;
            for (Fact fact : facts) {
                BigDecimal value = field.apply(fact);
                if (value == null) {
                    return null;
                }
                total = total.add(value);
            }
            return total;
        }
    }

    private static final class Index {
        private final Snapshot snapshot;
        private final NavigableSet<LocalDate> days = new TreeSet<>();
        private final TreeMap<LocalDate, List<Fact>> byDate = new TreeMap<>();
        private final Map<String, List<Fact>> byMaterial = new HashMap<>();

        private Index(Snapshot snapshot) {
            if (snapshot == null || snapshot.accountId() == null || snapshot.accountId().isBlank()
                    || snapshot.days() == null || snapshot.facts() == null) {
                throw new IllegalArgumentException("A validated account snapshot is required");
            }
            this.snapshot = snapshot;
            for (DayEvidence day : snapshot.days()) {
                if (day == null || day.date() == null || day.contentHash() == null || !days.add(day.date())) {
                    throw new IllegalArgumentException("Snapshot needs one evidence version per date");
                }
            }
            for (Fact fact : snapshot.facts()) {
                if (fact == null || fact.date() == null || fact.materialId() == null || fact.materialId().isBlank()
                        || !days.contains(fact.date())) {
                    throw new IllegalArgumentException("Facts must belong to a complete snapshot day");
                }
                byDate.computeIfAbsent(fact.date(), ignored -> new ArrayList<>()).add(fact);
                byMaterial.computeIfAbsent(fact.materialId(), ignored -> new ArrayList<>()).add(fact);
            }
            byMaterial.values().forEach(facts -> facts.sort(Comparator.comparing(Fact::date)));
        }

        private List<Fact> inRange(DateRange range) {
            return byDate.subMap(range.start(), true, range.end(), true).values().stream()
                    .flatMap(List::stream).toList();
        }

        private Coverage coverage(DateRange range) {
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
            List<Fact> selected = byMaterial.getOrDefault(id, List.of()).stream()
                    .filter(fact -> !fact.date().isBefore(range.start()) && !fact.date().isAfter(range.end())).toList();
            Coverage coverage = coverage(range);
            return new Totals(selected, coverage.coveredDays() > 0, !coverage.state().equals("COMPLETE"));
        }

        private List<DailySpend> dailySpend(String id, DateRange range) {
            // Group once; do not rescan a material's entire history for every plotted day.
            Map<LocalDate, List<Fact>> grouped = new HashMap<>();
            for (Fact fact : byMaterial.getOrDefault(id, List.of())) {
                if (!fact.date().isBefore(range.start()) && !fact.date().isAfter(range.end())) {
                    grouped.computeIfAbsent(fact.date(), ignored -> new ArrayList<>()).add(fact);
                }
            }
            List<DailySpend> result = new ArrayList<>();
            long count = ChronoUnit.DAYS.between(range.start(), range.end()) + 1;
            for (long offset = 0; offset < count; offset++) {
                LocalDate date = range.start().plusDays(offset);
                Totals totals = new Totals(grouped.getOrDefault(date, List.of()), days.contains(date), !days.contains(date));
                result.add(new DailySpend(date, totals.numberField(Fact::spend)));
            }
            return List.copyOf(result);
        }

        private String name(String id, LocalDate anchor) {
            String name = null;
            for (Fact fact : byMaterial.getOrDefault(id, List.of())) {
                if (fact.date().isAfter(anchor)) {
                    break;
                }
                if (fact.materialName() != null) {
                    name = fact.materialName();
                }
            }
            return name;
        }

        private FirstSpend firstSpend(String id, LocalDate anchor) {
            LocalDate earliest = null;
            Set<LocalDate> createdDates = new HashSet<>();
            List<Fact> facts = byMaterial.getOrDefault(id, List.of());
            for (Fact fact : facts) {
                if (fact.date().isAfter(anchor)) {
                    break;
                }
                if (fact.createdDate() != null) {
                    createdDates.add(fact.createdDate());
                }
                if (earliest == null && positive(fact.spend())) {
                    earliest = fact.date();
                }
            }
            boolean verified = false;
            if (earliest != null && createdDates.size() == 1) {
                LocalDate created = createdDates.iterator().next();
                if (!created.isAfter(earliest)) {
                    long expected = ChronoUnit.DAYS.between(created, earliest) + 1;
                    verified = days.subSet(created, true, earliest, true).size() == expected;
                    if (verified) {
                        for (Fact fact : facts) {
                            if (fact.date().isAfter(earliest)) {
                                break;
                            }
                            if (fact.spend() == null) {
                                verified = false;
                                break;
                            }
                        }
                    }
                }
            }
            return new FirstSpend(verified ? "VERIFIED" : "PENDING", verified ? earliest : null, earliest);
        }

        private String cohort(String id, DateRange range) {
            if (id.equals("-2")) return "aigc";
            CreationDate creation = creationDate(id, range.end());
            if (!creation.status().equals("VERIFIED")) return "pending";
            return creation.date().isBefore(range.start()) ? "historical" : "new";
        }

        private CreationDate creationDate(String id, LocalDate anchor) {
            return resolveCreationDate(byMaterial.getOrDefault(id, List.of()), anchor);
        }

        private String fingerprint() {
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
    }
}


