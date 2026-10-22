package cn.datadashboard.ingest.persistence;

import org.springframework.stereotype.Service;

@Service
public final class ManualHistoryImportService {

    private final ManualHistoryPreparedBatchReader batchReader;
    private final FactImportStore store;

    ManualHistoryImportService(ManualHistoryPreparedBatchReader batchReader, FactImportStore store) {
        this.batchReader = batchReader;
        this.store = store;
    }

    public FactImportReceipt importReady(ManualHistoryImportCommand command) {
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
                    "Manual history fact import transaction committed and read back");
        } catch (ReadbackVerificationException exception) {
            return rejected(
                    command,
                    FactImportErrorCode.DATABASE_READBACK_FAILED,
                    "Database readback verification failed; transaction was rolled back");
        } catch (RuntimeException exception) {
            return rejected(
                    command,
                    FactImportErrorCode.DATABASE_TRANSACTION_FAILED,
                    "Database transaction failed and was rolled back");
        }
    }

    private static FactImportReceipt rejected(
            ManualHistoryImportCommand command,
            FactImportErrorCode errorCode,
            String message
    ) {
        return new FactImportReceipt(
                command.runId(),
                null,
                null,
                null,
                FactImportStatus.REJECTED,
                null,
                null,
                0,
                errorCode,
                message);
    }
}
