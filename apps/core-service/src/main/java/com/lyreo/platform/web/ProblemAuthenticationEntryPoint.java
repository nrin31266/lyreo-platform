package com.lyreo.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Custom AuthenticationEntryPoint producing RFC 9457 Problem Details for 401 responses
 * while strictly preserving standard WWW-Authenticate Bearer challenge headers.
 */
@Component
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
    private final ApiProblemWriter problemWriter;

    public ProblemAuthenticationEntryPoint(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException
    ) throws IOException {
        delegate.commence(request, response, authException);

        ProblemDetail problem = ApiProblemFactory.create(
            HttpStatus.UNAUTHORIZED,
            "authentication-required",
            "Authentication required",
            "Authentication is required to access this resource.",
            ApiErrorCodes.AUTHENTICATION_REQUIRED,
            request,
            null
        );
        problemWriter.write(response, problem);
    }
}
