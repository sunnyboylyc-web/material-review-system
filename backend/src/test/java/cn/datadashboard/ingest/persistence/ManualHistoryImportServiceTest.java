package cn.datadashboard.ingest.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManualHistoryImportServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsUnverifiedDayBeforeCallingStore() {
        StubReader reader = new StubReader();
        reader.failure = new FactPreparationException(
                FactImportErrorCode.MANUAL_HISTORY_NOT_READY,
                "manual history day is staged");
        StubStore store = new StubStore();
        ManualHistoryImportService service = new ManualHistoryImportService(reader, store);
        ManualHistoryImportCommand command = new ManualHistoryImportCommand(
                "synthetic-run",
                temporaryDirectory.resolve("day-evidence.json"),
                temporaryDirectory);

        FactImportReceipt receipt = service.importReady(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.REJECTED);
        assertThat(receipt.errorCode()).isEqualTo(FactImportErrorCode.MANUAL_HISTORY_NOT_READY);
        assertThat(store.calls).isZero();
    }

    private static final class StubReader extends ManualHistoryPreparedBatchReader {
        private FactPreparationException failure;

        @Override
        PreparedFactBatch prepare(ManualHistoryImportCommand command) {
            throw failure;
        }
    }

    private static final class StubStore implements FactImportStore {
        private int calls;

        @Override
        public FactStoreResult importAtomically(PreparedFactBatch batch) {
            calls++;
            return null;
        }
    }
}
