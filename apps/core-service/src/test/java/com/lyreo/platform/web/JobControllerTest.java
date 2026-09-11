package com.lyreo.platform.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.CancellationResult;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.observability.CorrelationIdFilter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobControllerTest {

    private BackgroundJobService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(BackgroundJobService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    }

    @Test
    void getReturnsJobDetailsWhenFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.of(new BackgroundJob(
            id, "LESSON_BUILD", "lesson", UUID.randomUUID(),
            BackgroundJobStatus.RUNNING, 10, "GENERATE_TEXT", 40,
            1, 3, null, "worker-1", Instant.now().plusSeconds(60), null, null
        )));

        mockMvc.perform(get("/api/v1/jobs/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id.toString()))
            .andExpect(jsonPath("$.jobType").value("LESSON_BUILD"))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.currentStep").value("GENERATE_TEXT"))
            .andExpect(jsonPath("$.progressPercent").value(40));
    }

    @Test
    void getReturns404WhenJobNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/jobs/" + id))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void cancelReturns202WhenCancellationAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id)).thenReturn(CancellationResult.ACCEPTED);

        mockMvc.perform(post("/api/v1/jobs/" + id + "/cancel"))
            .andExpect(status().isAccepted())
            .andExpect(content().string(""));
    }

    @Test
    void cancelReturns404WhenJobNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id)).thenReturn(CancellationResult.NOT_FOUND);

        mockMvc.perform(post("/api/v1/jobs/" + id + "/cancel"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void cancelReturns409WhenJobNotCancellable() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id)).thenReturn(CancellationResult.NOT_CANCELLABLE);

        mockMvc.perform(post("/api/v1/jobs/" + id + "/cancel"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
            .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
            .andExpect(jsonPath("$.status").value(409));
    }
}
