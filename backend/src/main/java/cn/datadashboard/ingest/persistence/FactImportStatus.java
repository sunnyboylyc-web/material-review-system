package cn.datadashboard.ingest.persistence;

public enum FactImportStatus {
    IMPORTED_CURRENT,
    IMPORTED_ISOLATED,
    IDEMPOTENT,
    REJECTED
}
