package com.lyreo.platform.bootstrap;

import com.lyreo.platform.security.LocalRateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MVP inbound API protection.
 *
 * <p>This is intentionally a single-node Bucket4j+Caffeine limiter. It protects
 * accidental abuse and expensive endpoints without introducing Redis before a
 * distributed quota is actually required. Values are runtime config so the team
 * can tune dev/staging/prod without recompiling.</p>
 */
@Configuration
public class ApiRateLimitConfiguration {

    @Bean
    LocalRateLimitService apiRateLimitService(
        @Value("${lyreo.rate-limit.tokens-per-minute:120}") long tokensPerMinute,
        @Value("${lyreo.rate-limit.max-client-keys:100000}") long maxClientKeys
    ) {
        return new LocalRateLimitService(tokensPerMinute, Duration.ofMinutes(1), maxClientKeys);
    }

    @Bean
    OncePerRequestFilter apiRateLimitFilter(LocalRateLimitService limiter) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain
            ) throws ServletException, IOException {
                if (!request.getRequestURI().startsWith("/api/")) {
                    chain.doFilter(request, response);
                    return;
                }

                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String principal = auth != null && auth.isAuthenticated()
                    ? auth.getName()
                    : request.getRemoteAddr();
                String bucketKey = principal + ":" + coarseScope(request.getRequestURI());

                if (!limiter.tryConsume(bucketKey)) {
                    response.setStatus(429);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"RATE_LIMITED\"}");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static String coarseScope(String uri) {
        if (uri.contains("/chat")) return "chat";
        if (uri.contains("/speech")) return "speech";
        if (uri.contains("/lessons")) return "lesson";
        return "general";
    }
}
