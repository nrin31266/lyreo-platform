package com.lyreo.platform.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * Single authority for accessing and sanitizing correlation IDs.
 */
public final class CorrelationIdAccessor {
    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "com.lyreo.correlationId";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private CorrelationIdAccessor() {}

    /**
     * Sanitizes incoming correlation ID strings.
     * Rejects control characters, spaces, and invalid lengths to prevent log/header injection.
     */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.strip();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        return SAFE_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /**
     * Resolves the authoritative correlation ID for the request.
     * Order of precedence: request attribute -> sanitized header -> MDC.
     */
    public static String get(HttpServletRequest request) {
        if (request != null) {
            Object attr = request.getAttribute(ATTRIBUTE);
            if (attr instanceof String s && !s.isBlank()) {
                return s;
            }
            String fromHeader = sanitize(request.getHeader(HEADER));
            if (fromHeader != null) {
                return fromHeader;
            }
        }
        String mdc = MDC.get(MDC_KEY);
        return mdc != null && !mdc.isBlank() ? mdc : "";
    }
}
