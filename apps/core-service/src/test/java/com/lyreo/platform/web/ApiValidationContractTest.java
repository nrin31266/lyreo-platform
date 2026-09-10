package com.lyreo.platform.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.observability.CorrelationIdAccessor;
import com.lyreo.platform.observability.CorrelationIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiValidationContractTest {

    private MockMvc mockMvc;

    record SamplePayload(
        @NotBlank(message = "title is required")
        String title,
        @NotNull(message = "count is required")
        @Min(value = 1, message = "count must be at least 1")
        @Max(value = 100, message = "count must be at most 100")
        Integer count
    ) {}

    @RestController
    @RequestMapping("/test/validation")
    static class ValidationController {
        @PostMapping("/items")
        public ResponseEntity<SamplePayload> create(@Valid @RequestBody SamplePayload payload) {
            return ResponseEntity.ok(payload);
        }

        @GetMapping("/items/{id}")
        public ResponseEntity<String> getById(@PathVariable UUID id) {
            return ResponseEntity.ok(id.toString());
        }

        @GetMapping("/search")
        public ResponseEntity<String> search(@RequestParam("q") String query) {
            return ResponseEntity.ok(query);
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void validPayloadSucceeds() throws Exception {
        String json = """
            {
                "title": "Clean Architecture",
                "count": 5
            }
            """;

        mockMvc.perform(post("/test/validation/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Clean Architecture"))
            .andExpect(jsonPath("$.count").value(5));
    }

    @Test
    void invalidPayloadReturns400ProblemDetailWithViolations() throws Exception {
        String json = """
            {
                "title": "",
                "count": 0
            }
            """;

        mockMvc.perform(post("/test/validation/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(header().exists(CorrelationIdAccessor.HEADER))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:request-validation-failed"))
            .andExpect(jsonPath("$.title").value("Request validation failed"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.detail").value("One or more request fields are invalid."))
            .andExpect(jsonPath("$.instance").value("/test/validation/items"))
            .andExpect(jsonPath("$.correlationId").isString())
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.errors", hasSize(2)))
            .andExpect(jsonPath("$.errors[*].field", hasItem("title")))
            .andExpect(jsonPath("$.errors[*].field", hasItem("count")))
            .andExpect(jsonPath("$.errors[*].code", hasItem("NotBlank")))
            .andExpect(jsonPath("$.errors[*].code", hasItem("Min")))
            .andExpect(jsonPath("$.errors[*].rejectedValue").doesNotExist());
    }

    @Test
    void malformedJsonReturns400MalformedRequest() throws Exception {
        String malformedJson = "{ title: 'missing quotes' ";

        mockMvc.perform(post("/test/validation/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedJson))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:malformed-request"))
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Failed to read or parse request payload."));
    }

    @Test
    void typeMismatchReturns400MalformedRequest() throws Exception {
        mockMvc.perform(get("/test/validation/items/not-a-valid-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
            .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void missingRequiredQueryParamReturns400RequestValidationFailed() throws Exception {
        mockMvc.perform(get("/test/validation/search"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:request-validation-failed"))
            .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.errors").isArray())
            .andExpect(jsonPath("$.errors[0].field").value("q"));
    }

    @Test
    void methodNotAllowedReturns405ProblemDetail() throws Exception {
        mockMvc.perform(post("/test/validation/search"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:method-not-allowed"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedMediaTypeReturns415ProblemDetail() throws Exception {
        mockMvc.perform(post("/test/validation/items")
                .contentType(MediaType.TEXT_PLAIN)
                .content("hello world"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:unsupported-media-type"))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.status").value(415));
    }
}
