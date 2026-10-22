package cn.datadashboard.ingest.persistence;

import cn.datadashboard.ingest.validation.FactInputValidationReceipt;
import cn.datadashboard.ingest.validation.FactInputValidationRequest;
import cn.datadashboard.ingest.validation.InputFileEvidence;
import cn.datadashboard.ingest.validation.ValidationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
class VersionedFactImportServiceTest {

    private static final LocalDate STAT_DATE = LocalDate.of(2026, 10, 11);
    private static final String ACCOUNT_ID = "1000000000000001";
    private static final String HASH = "a".repeat(64);

    private StubBatchReader reader;
    private StubStore store;
    private VersionedFactImportService service;
    private VersionedFactImportCommand command;
    private PreparedFactBatch batch;

    @BeforeEach
    void setUp() {
        reader = new StubBatchReader();
        store = new StubStore();
        service = new VersionedFactImportService(reader, store);
        command = acceptedCommand();
        batch = preparedBatch();
    }

    @Test
    void reportsCommittedCurrentVersionOnlyAfterStoreReadback() {
        reader.batch = batch;
        store.result = new FactStoreResult(
                21L, FactVersionDisposition.CURRENT, FactStoreOutcome.INSERTED, 1);

        FactImportReceipt receipt = service.importValidated(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.IMPORTED_CURRENT);
        assertThat(receipt.committed()).isTrue();
        assertThat(receipt.inputVersionId()).isEqualTo(21L);
        assertThat(receipt.errorCode()).isNull();
    }

    @Test
    void reportsThirdDifferentVersionAsIsolated() {
        reader.batch = batch;
        store.result = new FactStoreResult(
                23L, FactVersionDisposition.ISOLATED, FactStoreOutcome.INSERTED, 1);

        FactImportReceipt receipt = service.importValidated(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.IMPORTED_ISOLATED);
        assertThat(receipt.disposition()).isEqualTo(FactVersionDisposition.ISOLATED);
    }

    @Test
    void reportsSameContentHashAsIdempotent() {
        reader.batch = batch;
        store.result = new FactStoreResult(
                21L, FactVersionDisposition.CURRENT, FactStoreOutcome.IDEMPOTENT, 1);

        FactImportReceipt receipt = service.importValidated(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.IDEMPOTENT);
        assertThat(receipt.inputVersionId()).isEqualTo(21L);
    }

    @Test
    void rejectsBeforeDatabaseWhenSourceChanged() {
        reader.failure = new FactPreparationException(
                FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION,
                "materialDay source changed after validation");

        FactImportReceipt receipt = service.importValidated(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.REJECTED);
        assertThat(receipt.errorCode()).isEqualTo(FactImportErrorCode.SOURCE_CHANGED_AFTER_VALIDATION);
        assertThat(store.calls).isZero();
    }

    @Test
    void reportsReadbackFailureAsRejectedTransaction() {
        reader.batch = batch;
        store.failure = new ReadbackVerificationException("mismatch");

        FactImportReceipt receipt = service.importValidated(command);

        assertThat(receipt.status()).isEqualTo(FactImportStatus.REJECTED);
        assertThat(receipt.committed()).isFalse();
        assertThat(receipt.errorCode()).isEqualTo(FactImportErrorCode.DATABASE_READBACK_FAILED);
        assertThat(receipt.inputVersionId()).isNull();
    }

    private VersionedFactImportCommand acceptedCommand() {
        Path input = Path.of("synthetic-input").toAbsolutePath().normalize();
        FactInputValidationRequest request = new FactInputValidationRequest(
                "run-m2-service", input, ACCOUNT_ID, STAT_DATE);
        InputFileEvidence manifest = new InputFileEvidence(
                "manifest.json", input.resolve("manifest.json").toString(), 10, "b".repeat(64));
        InputFileEvidence material = new InputFileEvidence(
                "material_day.json", input.resolve("material_day.json").toString(), 20, "c".repeat(64));
        FactInputValidationReceipt receipt = new FactInputValidationReceipt(
                request.runId(),
                ValidationStatus.ACCEPTED,
                ACCOUNT_ID,
                STAT_DATE,
                "synthetic-project",
                HASH,
                1,
                1,
                manifest,
                material,
                List.of());
        return new VersionedFactImportCommand(request, receipt);
    }

    private PreparedFactBatch preparedBatch() {
        InputFileEvidence manifest = command.validationReceipt().manifestFile();
        InputFileEvidence material = command.validationReceipt().materialDayFile();
        return new PreparedFactBatch(
                command.validationRequest().runId(),
                "synthetic-project",
                ACCOUNT_ID,
                STAT_DATE,
                FactSourceType.MCP_AUTO,
                "synthetic-version",
                HASH,
                "synthetic-schema",
                "synthetic-catalog",
                manifest,
                material,
                List.of(new PreparedMaterialFact(
                        "synthetic-material",
                        1,
                        "{\"material_id\":\"synthetic-material\"}",
                        "d".repeat(64))));
    }

    private static final class StubBatchReader extends PreparedFactBatchReader {
        private PreparedFactBatch batch;
        private FactPreparationException failure;

        @Override
        PreparedFactBatch prepare(VersionedFactImportCommand command) {
            if (failure != null) {
                throw failure;
            }
            return batch;
        }
    }

    private static final class StubStore implements FactImportStore {
        private FactStoreResult result;
        private RuntimeException failure;
        private int calls;

        @Override
        public FactStoreResult importAtomically(PreparedFactBatch batch) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
