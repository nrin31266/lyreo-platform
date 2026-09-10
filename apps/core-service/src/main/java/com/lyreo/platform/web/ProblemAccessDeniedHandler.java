package com.lyreo.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Custom AccessDeniedHandler producing RFC 9457 Problem Details for 403 responses
 * without leaking authorization internals.
 */
@Component
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {
    private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();
    private final ApiProblemWriter problemWriter;

    public ProblemAccessDeniedHandler(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        delegate.handle(request, response, accessDeniedException);

        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.FORBIDDEN,
            "access-denied",
            "Access denied",
            "Access is denied to this resource.",
            ApiErrorCodes.ACCESS_DENIED,
            request,
            null
        );
        problemWriter.write(response, problem);
    }
}
