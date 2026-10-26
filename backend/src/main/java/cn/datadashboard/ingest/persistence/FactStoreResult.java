package cn.datadashboard.ingest.persistence;

public record FactStoreResult(
        long inputVersionId,
        FactVersionDisposition disposition,
        FactStoreOutcome outcome,
        int rowCount
) {
}
