package com.lyreo.contracts.errors;

/**
 * Thrown when a requested resource or aggregate cannot be found by its identifier or criteria.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
