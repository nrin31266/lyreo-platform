package com.lyreo.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.bootstrap.ApiRateLimitConfiguration;
import com.lyreo.platform.observability.CorrelationIdFilter;
import com.lyreo.platform.security.LocalRateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

class ApiRateLimitContractTest {

    private MockMvc mockMvc;
    private LocalRateLimitService rateLimitService;

    @RestController
    static class RateLimitTestController {
        @GetMapping("/api/v1/test/limited")
        public ResponseEntity<String> limited() {
            return ResponseEntity.ok("success");
        }
    }

    @BeforeEach
    void setUp() {
        // 2 requests per minute capacity
        rateLimitService = new LocalRateLimitService(2, java.time.Duration.ofMinutes(1));
        ApiRateLimitConfiguration config = new ApiRateLimitConfiguration();
        OncePerRequestFilter filter = config.apiRateLimitFilter(rateLimitService, new ApiProblemWriter());

        mockMvc = MockMvcBuilders.standaloneSetup(new RateLimitTestController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter(), filter)
            .build();
    }

    @Test
    void allowsRequestsWithinLimitThenReturns429WithRetryAfter() throws Exception {
        // Request 1: OK
        mockMvc.perform(get("/api/v1/test/limited"))
            .andExpect(status().isOk());

        // Request 2: OK
        mockMvc.perform(get("/api/v1/test/limited"))
            .andExpect(status().isOk());

        // Request 3: Exceeded -> 429
        mockMvc.perform(get("/api/v1/test/limited"))
            .andExpect(status().isTooManyRequests())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:rate-limited"))
            .andExpect(jsonPath("$.title").value("Rate limit exceeded"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Too many requests. Please try again in ")))
            .andExpect(jsonPath("$.correlationId").isString());
    }
}
