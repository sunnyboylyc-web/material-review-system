package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.FactInputValidationReceipt;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;

import java.util.Objects;

public record VersionedFactImportCommand(
        FactInputValidationRequest validationRequest,
        FactInputValidationReceipt validationReceipt
) {
    public VersionedFactImportCommand {
        Objects.requireNonNull(validationRequest, "validationRequest must not be null");
        Objects.requireNonNull(validationReceipt, "validationReceipt must not be null");
    }
}
