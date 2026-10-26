package cn.datadashboard.ingest.persistence;

public interface FactImportStore {

    FactStoreResult importAtomically(PreparedFactBatch batch);
}
