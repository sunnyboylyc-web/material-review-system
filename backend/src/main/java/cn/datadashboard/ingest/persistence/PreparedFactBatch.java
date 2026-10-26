package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.InputFileEvidence;

import java.time.LocalDate;
import java.util.List;

public record PreparedFactBatch(
        String runId,
        String projectRef,
        String accountId,
        LocalDate statDate,
        FactSourceType sourceType,
        String dataVersion,
        String contentHash,
        String schemaVersion,
        String catalogVersion,
        InputFileEvidence manifestEvidence,
        InputFileEvidence materialEvidence,
        List<PreparedMaterialFact> rows
) {
    public PreparedFactBatch {
        rows = List.copyOf(rows);
    }
}
