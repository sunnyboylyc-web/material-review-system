package cn.datadashboard.ingest.validation;

public record InputFileEvidence(
        String fileName,
        String absolutePath,
        long sizeBytes,
        String sha256
) {
}
