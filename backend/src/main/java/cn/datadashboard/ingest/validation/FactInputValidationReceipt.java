package cn.datadashboard.ingest.validation;

import java.time.LocalDate;
import java.util.List;

public record FactInputValidationReceipt(
        String runId,
        ValidationStatus status,
        String expectedAdvertiserId,
        LocalDate expectedStatDate,
        String observedProjectRef,
        String observedDataHash,
        Integer pageCount,
        Integer reportRowCount,
        InputFileEvidence manifestFile,
        InputFileEvidence materialDayFile,
        List<ValidationIssue> issues
) {
    public FactInputValidationReceipt {
        issues = List.copyOf(issues);
    }

    public boolean accepted() {
        return status == ValidationStatus.ACCEPTED;
    }
}
