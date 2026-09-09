package com.lyreo.contracts.errors;

/**
 * Thrown when an incoming application request or command fails parameter, syntax, or input validation.
 */
public class RequestValidationException extends RuntimeException {
    public RequestValidationException(String message) {
        super(message);
    }

    public RequestValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
