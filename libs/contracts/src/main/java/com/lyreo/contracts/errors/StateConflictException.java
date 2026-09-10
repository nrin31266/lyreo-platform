package com.lyreo.contracts.errors;

/**
 * Thrown when an operation conflicts with current entity, workflow, capability policy, or business state.
 */
public class StateConflictException extends RuntimeException {
    public StateConflictException(String message) {
        super(message);
    }

    public StateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
