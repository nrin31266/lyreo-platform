package com.lyreo.ai.application;

/**
 * Failure of one AI route/capability execution.
 *
 * <p>{@code fallbackAllowed} means trying the next configured provider/model can reasonably
 * succeed (network error, provider outage, quota, invalid credential/model route, ...). A bad
 * business request/schema is marked non-fallback so Lyreo does not spend money repeating the
 * same invalid request against every provider.</p>
 */
public final class AiExecutionException extends RuntimeException {
    private final boolean fallbackAllowed;
    private final String errorCode;

    public AiExecutionException(
        String message,
        boolean fallbackAllowed,
        String errorCode,
        Throwable cause
    ) {
        super(message, cause);
        this.fallbackAllowed = fallbackAllowed;
        this.errorCode = errorCode;
    }

    public AiExecutionException(String message, boolean fallbackAllowed, String errorCode) {
        this(message, fallbackAllowed, errorCode, null);
    }

    public boolean fallbackAllowed() {
        return fallbackAllowed;
    }

    public String errorCode() {
        return errorCode;
    }
}
