package com.lyreo.contracts.errors;

/** Expected dependency failure with a safe public message and stable application code. */
public final class ServiceUnavailableException extends RuntimeException {
    private final String errorCode;

    public ServiceUnavailableException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() { return errorCode; }
}
