package cn.datadashboard.ingest.persistence;

import org.springframework.stereotype.Service;

@Service
public final class VersionedFactImportService {

    private final PreparedFactBatchReader batchReader;
    private final FactImportStore store;

    VersionedFactImportService(PreparedFactBatchReader batchReader, FactImportStore store) {
        this.batchReader = batchReader;
        this.store = store;
    }

    public FactImportReceipt importValidated(VersionedFactImportCommand command) {
        PreparedFactBatch batch;
        try {
            batch = batchReader.prepare(command);
        } catch (FactPreparationException exception) {
            return rejected(command, exception.errorCode(), exception.getMessage());
        }

        try {
            FactStoreResult stored = store.importAtomically(batch);
            FactImportStatus status;
            if (stored.outcome() == FactStoreOutcome.IDEMPOTENT) {
                status = FactImportStatus.IDEMPOTENT;
            } else if (stored.disposition() == FactVersionDisposition.ISOLATED) {
                status = FactImportStatus.IMPORTED_ISOLATED;
            } else {
                status = FactImportStatus.IMPORTED_CURRENT;
            }
            return new FactImportReceipt(
                    batch.runId(),
                    batch.accountId(),
                    batch.statDate(),
                    batch.contentHash(),
                    status,
                    stored.inputVersionId(),
                    stored.disposition(),
                    stored.rowCount(),
                    null,
                    "Fact import transaction committed and read back");
        } catch (ReadbackVerificationException exception) {
            return rejected(command, FactImportErrorCode.DATABASE_READBACK_FAILED,
                    "Database readback verification failed; transaction was rolled back");
        } catch (RuntimeException exception) {
            return rejected(command, FactImportErrorCode.DATABASE_TRANSACTION_FAILED,
                    "Database transaction failed and was rolled back");
        }
    }

    private static FactImportReceipt rejected(
            VersionedFactImportCommand command,
            FactImportErrorCode errorCode,
            String message
    ) {
        var request = command.validationRequest();
        return new FactImportReceipt(
                request.runId(),
                request.expectedAdvertiserId(),
                request.expectedStatDate(),
                command.validationReceipt().observedDataHash(),
                FactImportStatus.REJECTED,
                null,
                null,
                0,
                errorCode,
                message);
    }
}
