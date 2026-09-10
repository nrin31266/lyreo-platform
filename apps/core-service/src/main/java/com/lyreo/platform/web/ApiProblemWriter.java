package com.lyreo.platform.web;

import com.lyreo.platform.observability.CorrelationIdAccessor;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes ProblemDetail directly to HttpServletResponse with application/problem+json.
 * Uses the injected Spring Boot auto-configured Jackson ObjectMapper bean.
 */
@Component
public final class ApiProblemWriter {
    public static final MediaType APPLICATION_PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    private final ObjectMapper objectMapper;

    public ApiProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(APPLICATION_PROBLEM_JSON.toString());
        response.setCharacterEncoding("UTF-8");
        Object correlationId = problem.getProperties() != null ? problem.getProperties().get("correlationId") : null;
        if (correlationId instanceof String cid && !cid.isBlank()) {
            response.setHeader(CorrelationIdAccessor.HEADER, cid);
        }
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
