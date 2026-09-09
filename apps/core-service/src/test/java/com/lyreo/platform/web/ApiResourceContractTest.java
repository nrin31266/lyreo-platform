package com.lyreo.platform.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.platform.observability.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiResourceContractTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test/resources")
    static class ResourceTestController {
        @GetMapping("/missing")
        public void missing() {
            throw new ResourceNotFoundException("Vocabulary card not found: 123");
        }

        @GetMapping("/conflict")
        public void conflict() {
            throw new StateConflictException("Job is already running");
        }

        @GetMapping("/invalid")
        public void invalid() {
            throw new RequestValidationException("defaultPlaybackSpeed must be between 0.5 and 2.0");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ResourceTestController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void resourceNotFoundProduces404ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/resources/missing"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:resource-not-found"))
            .andExpect(jsonPath("$.title").value("Resource not found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.detail").value("Vocabulary card not found: 123"))
            .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void stateConflictProduces409ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/resources/conflict"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:state-conflict"))
            .andExpect(jsonPath("$.title").value("State conflict"))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
            .andExpect(jsonPath("$.detail").value("Job is already running"))
            .andExpect(jsonPath("$.correlationId").isString());
    }

    @Test
    void requestValidationProduces400ProblemDetail() throws Exception {
        mockMvc.perform(get("/test/resources/invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:request-validation-failed"))
            .andExpect(jsonPath("$.title").value("Request validation failed"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("defaultPlaybackSpeed must be between 0.5 and 2.0"))
            .andExpect(jsonPath("$.correlationId").isString());
    }
}
