package cn.datadashboard.ingest.persistence;

import java.nio.file.Path;
import java.util.Objects;

public record ManualHistoryImportCommand(
        String runId,
        Path dayEvidencePath,
        Path allowedDataRoot
) {
    public ManualHistoryImportCommand {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(dayEvidencePath, "dayEvidencePath must not be null");
        Objects.requireNonNull(allowedDataRoot, "allowedDataRoot must not be null");
    }
}
