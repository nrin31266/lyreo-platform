package com.lyreo.lesson.application;

/** Failure while converting an external lesson source into processable media. */
public final class SourceMaterializationException extends RuntimeException {
    private final boolean retryable;
    private final String errorCode;

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
}
