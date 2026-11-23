package com.lyreo.lesson.application.build;

import java.util.Map;

/** Failure while converting an external lesson source into processable media. */
public final class SourceMaterializationException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;
    private Map<String, Object> diagnostics = Map.of();

    public SourceMaterializationException(
        String errorCode,
        String message,
        boolean retryable
    ) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public SourceMaterializationException(
        String errorCode,
        String message,
        boolean retryable,
        Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public Map<String, Object> diagnostics() {
        return diagnostics;
    }

    public SourceMaterializationException withDiagnostics(Map<String, Object> evidence) {
        diagnostics = Map.copyOf(evidence);
        return this;
    }
}
