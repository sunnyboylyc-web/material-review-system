package cn.datadashboard.ingest.validation;

import java.util.Objects;

public record ValidationIssue(
        ValidationIssueCode code,
        String field,
        String message
) {
    public ValidationIssue {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
