package com.lyreo.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> badRequest(IllegalArgumentException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", error.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(IllegalStateException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_ERROR", error.getMessage(), request);
    }

    private ResponseEntity<?> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
            "code", code,
            "message", message == null ? status.getReasonPhrase() : message,
            "correlationId", request.getHeader("X-Correlation-Id") == null ? "" : request.getHeader("X-Correlation-Id")
        ));
    }
}
