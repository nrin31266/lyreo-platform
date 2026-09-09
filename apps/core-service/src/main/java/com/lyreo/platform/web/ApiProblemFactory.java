package com.lyreo.platform.web;

import com.lyreo.platform.observability.CorrelationIdAccessor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Factory for creating standard RFC 9457 ProblemDetail objects.
 */
public final class ApiProblemFactory {
    public static final String URN_PREFIX = "urn:lyreo:problem:";

    private ApiProblemFactory() {}

    public static ProblemDetail create(
        HttpStatus status,
        String slug,
        String title,
        String detail,
        String code,
        HttpServletRequest request,
        List<ApiFieldViolation> errors
    ) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(URN_PREFIX + slug));
        problem.setTitle(title);
        problem.setDetail(detail);
        if (request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        problem.setProperty("code", code);
        problem.setProperty("correlationId", CorrelationIdAccessor.get(request));
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }
}
