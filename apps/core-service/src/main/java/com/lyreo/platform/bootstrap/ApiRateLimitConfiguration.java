package com.lyreo.platform.bootstrap;

import com.lyreo.platform.security.LocalRateLimitService;
import com.lyreo.platform.web.ApiErrorCodes;
import com.lyreo.platform.web.ApiProblemFactory;
import com.lyreo.platform.web.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
    public LocalRateLimitService apiRateLimitService(
        @Value("${lyreo.rate-limit.tokens-per-minute:120}") long tokensPerMinute,
        @Value("${lyreo.rate-limit.max-client-keys:100000}") long maxClientKeys
    ) {
        return new LocalRateLimitService(tokensPerMinute, Duration.ofMinutes(1), maxClientKeys);
    }

    @Bean
    public OncePerRequestFilter apiRateLimitFilter(LocalRateLimitService limiter, ApiProblemWriter problemWriter) {
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

                var result = limiter.tryConsumeProbe(bucketKey);
                if (!result.allowed()) {
                    long waitSeconds = result.secondsToWaitForRefill();
                    response.setHeader("Retry-After", String.valueOf(waitSeconds));

                    ProblemDetail problem = ApiProblemFactory.create(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "rate-limited",
                        "Rate limit exceeded",
                        "Too many requests. Please try again in " + waitSeconds + " seconds.",
                        ApiErrorCodes.RATE_LIMITED,
                        request,
                        null
                    );
                    problemWriter.write(response, problem);
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
