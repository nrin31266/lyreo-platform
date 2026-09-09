package com.lyreo.platform.web;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.observability.CorrelationIdAccessor;
import com.lyreo.platform.observability.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiErrorContractTest {

    private MockMvc mockMvc;

    @RestController
    @RequestMapping("/test/errors")
    static class FaultyController {
        @GetMapping("/sql-leak")
        public void sqlLeak() {
            throw new RuntimeException("SELECT * FROM users WHERE password_hash = 'secret_123'; syntax error");
        }

        @GetMapping("/npe")
        public void nullPointer() {
            String s = null;
            s.length();
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FaultyController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void unhandledExceptionReturns500WithSafeMessageAndNoLeak() throws Exception {
        mockMvc.perform(get("/test/errors/sql-leak")
                .header(CorrelationIdAccessor.HEADER, "corr-safe-500"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(header().string(CorrelationIdAccessor.HEADER, "corr-safe-500"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:internal-error"))
            .andExpect(jsonPath("$.title").value("Internal server error"))
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."))
            .andExpect(jsonPath("$.correlationId").value("corr-safe-500"))
            .andExpect(jsonPath("$.detail", not(org.hamcrest.Matchers.containsString("password_hash"))))
            .andExpect(jsonPath("$.stackTrace").doesNotExist())
            .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void nullPointerExceptionReturns500WithSafeMessage() throws Exception {
        mockMvc.perform(get("/test/errors/npe"))
            .andExpect(status().isInternalServerError())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.type").value("urn:lyreo:problem:internal-error"))
            .andExpect(jsonPath("$.title").value("Internal server error"))
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("An unexpected server error occurred."))
            .andExpect(jsonPath("$.correlationId").isString());
    }
}
