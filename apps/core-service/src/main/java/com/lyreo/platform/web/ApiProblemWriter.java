package com.lyreo.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.platform.observability.CorrelationIdAccessor;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Writes ProblemDetail directly to HttpServletResponse with application/problem+json.
 * Used by security entry points, access denied handlers, and rate limit filters.
 */
@Component
public final class ApiProblemWriter {
    public static final MediaType APPLICATION_PROBLEM_JSON = MediaType.parseMediaType("application/problem+json");

    private final ObjectMapper objectMapper;

    public ApiProblemWriter() {
        this(new ObjectMapper());
    }

    public ApiProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.objectMapper.addMixIn(ProblemDetail.class, org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
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
