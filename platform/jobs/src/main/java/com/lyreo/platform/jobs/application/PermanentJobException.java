package com.lyreo.platform.jobs.application;

/**
 * Marks a job failure that will not become healthy by waiting/retrying the same input.
 *
 * <p>Examples: rejected product configuration, malformed provider payload, unsupported source.
 * Durable workers fail these immediately instead of consuming retry budget and external API cost.</p>
 */
public final class PermanentJobException extends RuntimeException {
    private final String errorCode;

    public PermanentJobException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PermanentJobException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
