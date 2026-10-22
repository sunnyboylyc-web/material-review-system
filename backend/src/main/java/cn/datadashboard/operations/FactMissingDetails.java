package cn.datadashboard.operations;

import cn.datadashboard.calculation.DashboardCalculator;
import cn.datadashboard.calculation.DashboardModels.*;
import java.time.LocalDate;
import java.util.*;

/** Read-only field observations. Absence does not establish a collector failure or its cause. */
public final class FactMissingDetails {
    private FactMissingDetails() { }
    public static List<OperationsStatus.MissingDetail> inspect(Snapshot snapshot, LocalDate start, LocalDate end) {
        Map<LocalDate, Long> versions = new HashMap<>();
        snapshot.days().forEach(day -> versions.put(day.date(), day.versionId()));
        Map<String, List<Fact>> byMaterial = new HashMap<>();
        snapshot.facts().forEach(fact -> byMaterial.computeIfAbsent(fact.materialId(), ignored -> new ArrayList<>()).add(fact));
        Map<String, CreationDate> creation = new HashMap<>();
        byMaterial.forEach((id, rows) -> creation.put(id, DashboardCalculator.inspectSourceCreationDate(rows, end)));
        List<OperationsStatus.MissingDetail> result = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (!versions.containsKey(date)) result.add(new OperationsStatus.MissingDetail(date, null,
                    "accountDay", "NO_CURRENT_VERSION", List.of("all"), null));
        }
        for (Fact fact : snapshot.facts()) {
            if (fact.date().isBefore(start) || fact.date().isAfter(end)) continue;
            CreationDate resolved = creation.get(fact.materialId());
            if (!fact.materialId().equals("-2") && (fact.createdDate() == null || !resolved.status().equals("VERIFIED"))) {
                String reason = !resolved.status().equals("VERIFIED") ? resolved.reason() : "MISSING_CREATION_DATE";
                add(result, fact, versions, "createdDate", reason, List.of());
            }
            boolean eligibleOrUnknown = fact.spend() == null || fact.spend().signum() > 0;
            if (fact.spend() == null) add(result, fact, versions, "spend", "FIELD_NOT_RECORDED", List.of("all"));
            if (fact.gmv() == null) add(result, fact, versions, "gmv", "FIELD_NOT_RECORDED", eligibleOrUnknown ? List.of("roi") : List.of());
            if (fact.impressions() == null) add(result, fact, versions, "impressions", "FIELD_NOT_RECORDED", eligibleOrUnknown ? List.of("ctr", "cpm") : List.of());
            if (fact.clicks() == null) add(result, fact, versions, "clicks", "FIELD_NOT_RECORDED", eligibleOrUnknown ? List.of("ctr", "cvr", "cpc") : List.of());
            if (fact.orders() == null) add(result, fact, versions, "orders", "FIELD_NOT_RECORDED", eligibleOrUnknown ? List.of("orders", "cvr") : List.of());
        }
        result.sort(Comparator.comparing(OperationsStatus.MissingDetail::date)
                .thenComparing(row -> row.materialId() == null ? "" : row.materialId())
                .thenComparing(OperationsStatus.MissingDetail::field));
        return List.copyOf(result);
    }
    private static void add(List<OperationsStatus.MissingDetail> result, Fact fact, Map<LocalDate, Long> versions,
                            String field, String reason, List<String> affected) {
        result.add(new OperationsStatus.MissingDetail(fact.date(), fact.materialId(), field, reason, affected, versions.get(fact.date())));
    }
}
