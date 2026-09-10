package com.lyreo.platform.web;

/**
 * Detailed field-level validation violation included in ProblemDetail.
 */
public record ApiFieldViolation(
    String field,
    String code,
    String message
) {}
