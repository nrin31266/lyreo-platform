package com.lyreo.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures every incoming HTTP request receives a valid, sanitized correlation ID
 * before any security, rate-limiting, or MVC handler executes.
 */
public final class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationIdAccessor.HEADER);
        String correlationId = CorrelationIdAccessor.sanitize(incoming);
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(CorrelationIdAccessor.ATTRIBUTE, correlationId);
        MDC.put(CorrelationIdAccessor.MDC_KEY, correlationId);
        response.setHeader(CorrelationIdAccessor.HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIdAccessor.MDC_KEY);
        }
    }
}
