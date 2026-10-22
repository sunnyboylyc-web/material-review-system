package cn.datadashboard.ingest.validation;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;

public record FactInputValidationRequest(
        String runId,
        Path inputDirectory,
        String expectedAdvertiserId,
        LocalDate expectedStatDate
) {
    public FactInputValidationRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(inputDirectory, "inputDirectory must not be null");
        if (expectedAdvertiserId == null || expectedAdvertiserId.isBlank()) {
            throw new IllegalArgumentException("expectedAdvertiserId must not be blank");
        }
        Objects.requireNonNull(expectedStatDate, "expectedStatDate must not be null");
        runId = runId.trim();
        expectedAdvertiserId = expectedAdvertiserId.trim();
    }
}
