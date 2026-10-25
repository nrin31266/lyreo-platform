package com.lyreo.platform.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.jobs.infrastructure.BackgroundJobWorker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobFailureBoundaryTest {
    @Test void unexpectedFailureKeepsDatabaseDetailsOutOfRetryAndTerminalMessages() throws Exception {
        for (int attempt : List.of(1, 3)) {
            var repository = mock(BackgroundJobRepository.class);
            var transitions = mock(JobStateTransitionService.class);
            var handler = mock(JobHandler.class);
            var job = new BackgroundJob(UUID.randomUUID(), "TEST", "lesson", UUID.randomUUID(),
                BackgroundJobStatus.RUNNING, 0, "TRANSCRIBING", 0, attempt, 3, null, "worker",
                Instant.now().plusSeconds(60), null, null);
            when(repository.claim(anyString(), anyInt(), any())).thenReturn(List.of(job));
            when(repository.findById(job.id())).thenReturn(Optional.of(job));
            when(handler.jobType()).thenReturn("TEST");
            doThrow(new IllegalStateException("SQL INSERT INTO private_table; credential=secret"))
                .when(handler).execute(eq(job), any());
            try (var worker = new BackgroundJobWorker(repository, List.of(handler), transitions,
                "worker", Duration.ofSeconds(60), 1)) {
                worker.runOnce();
            }
            String safe = "An unexpected processing error occurred. Retry or contact an administrator with the job ID.";
            if (attempt == 1) {
                verify(transitions).retryWaiting(eq(job), eq("worker"), any(), eq("JOB_EXECUTION_FAILED"),
                    eq(safe), eq("TRANSCRIBING"), eq(0));
            } else {
                verify(transitions).failed(eq(job), eq("worker"), eq("JOB_EXECUTION_FAILED"),
                    eq(safe), eq("TRANSCRIBING"), eq(0));
            }
        }
    }
}
