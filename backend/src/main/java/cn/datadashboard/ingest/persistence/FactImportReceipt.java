package cn.datadashboard.ingest.persistence;

import java.time.LocalDate;

public record FactImportReceipt(
        String runId,
        String accountId,
        LocalDate statDate,
        String contentHash,
        FactImportStatus status,
        Long inputVersionId,
        FactVersionDisposition disposition,
        int rowCount,
        FactImportErrorCode errorCode,
        String message
) {
    public boolean committed() {
        return status != FactImportStatus.REJECTED;
    }
}
