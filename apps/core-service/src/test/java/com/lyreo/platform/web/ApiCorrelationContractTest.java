package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.observability.CorrelationIdAccessor;
import com.lyreo.platform.observability.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiCorrelationContractTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {
        @GetMapping("/test/ping")
        public ResponseEntity<String> ping() {
            return ResponseEntity.ok("pong");
        }

        @GetMapping("/test/boom")
        public ResponseEntity<Void> boom() {
            throw new RuntimeException("Unexpected test failure");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void preservesValidIncomingCorrelationId() throws Exception {
        String customId = "custom-corr-12345";

        mockMvc.perform(get("/test/ping").header(CorrelationIdAccessor.HEADER, customId))
            .andExpect(status().isOk())
            .andExpect(header().string(CorrelationIdAccessor.HEADER, customId));
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        var result = mockMvc.perform(get("/test/ping"))
            .andExpect(status().isOk())
            .andExpect(header().exists(CorrelationIdAccessor.HEADER))
            .andReturn();

        String generated = result.getResponse().getHeader(CorrelationIdAccessor.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(generated).matches("^[a-zA-Z0-9_-]{1,64}$");
    }

    @Test
    void sanitizesInvalidIncomingCorrelationId() throws Exception {
        String invalidId = "bad/id;with spaces and $pecial chars!";

        var result = mockMvc.perform(get("/test/ping").header(CorrelationIdAccessor.HEADER, invalidId))
            .andExpect(status().isOk())
            .andExpect(header().exists(CorrelationIdAccessor.HEADER))
            .andReturn();

        String generated = result.getResponse().getHeader(CorrelationIdAccessor.HEADER);
        assertThat(generated).isNotEqualTo(invalidId);
        assertThat(generated).matches("^[a-zA-Z0-9_-]{1,64}$");
    }

    @Test
    void errorResponseProblemDetailContainsMatchingCorrelationId() throws Exception {
        String customId = "error-trace-999";

        mockMvc.perform(get("/test/boom").header(CorrelationIdAccessor.HEADER, customId))
            .andExpect(status().isInternalServerError())
            .andExpect(header().string(CorrelationIdAccessor.HEADER, customId))
            .andExpect(jsonPath("$.correlationId").value(customId))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }
}
