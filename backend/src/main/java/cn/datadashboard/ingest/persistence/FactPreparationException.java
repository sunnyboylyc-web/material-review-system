package cn.datadashboard.ingest.persistence;

final class FactPreparationException extends RuntimeException {

    private final FactImportErrorCode errorCode;

    FactPreparationException(FactImportErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    FactPreparationException(FactImportErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    FactImportErrorCode errorCode() {
        return errorCode;
    }
}
