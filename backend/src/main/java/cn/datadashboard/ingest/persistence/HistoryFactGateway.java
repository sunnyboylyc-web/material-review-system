package cn.datadashboard.ingest.persistence;

import java.time.LocalDate;
import java.util.Set;
import java.util.OptionalLong;

public interface HistoryFactGateway {
    record Context(LocalDate firstMcpDate, Set<LocalDate> existingDates, Set<LocalDate> manualHistoryDates) {
        public Context { existingDates = Set.copyOf(existingDates); manualHistoryDates = Set.copyOf(manualHistoryDates); }
        public Context(LocalDate firstMcpDate, Set<LocalDate> existingDates) { this(firstMcpDate, existingDates, Set.of()); }
    }
    Context inspect(String accountId);
    FactStoreResult importNewDay(PreparedFactBatch batch);
    /** Read-only recovery of this exact upload's committed day; never adopts another upload. */
    default OptionalLong recoverCurrentDay(PreparedFactBatch batch) { return OptionalLong.empty(); }
    void verify(PreparedFactBatch batch, long versionId);
}
