package cn.datadashboard.ingest.persistence;

public record PreparedMaterialFact(
        String materialId,
        int sourceRowNumber,
        String rawFactJson,
        String rowSha256
) {
}
