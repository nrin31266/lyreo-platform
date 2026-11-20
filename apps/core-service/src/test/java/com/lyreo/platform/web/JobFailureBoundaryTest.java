package com.lyreo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Test void claimsEachJobImmediatelyBeforeExecutingIt() throws Exception {
        var repository = mock(BackgroundJobRepository.class);
        var transitions = mock(JobStateTransitionService.class);
        var handler = mock(JobHandler.class);
        var first = mock(BackgroundJob.class);
        var second = mock(BackgroundJob.class);
        var lease = Duration.ofSeconds(60);
        when(first.jobType()).thenReturn("TEST");
        when(second.jobType()).thenReturn("TEST");
        when(handler.jobType()).thenReturn("TEST");
        when(repository.claim("worker", 1, lease)).thenReturn(List.of(first), List.of(second), List.of());
        when(repository.heartbeat(any(), eq("worker"), eq(lease))).thenReturn(true);
        when(transitions.succeeded(any(), eq("worker"))).thenReturn(true);

        try (var worker = new BackgroundJobWorker(repository, List.of(handler), transitions, "worker", lease, 5)) {
            assertThat(worker.runOnce()).isEqualTo(2);
        }
        var order = inOrder(repository, handler);
        order.verify(repository).claim("worker", 1, lease);
        order.verify(handler).execute(eq(first), any());
        order.verify(repository).claim("worker", 1, lease);
        order.verify(handler).execute(eq(second), any());
        order.verify(repository).claim("worker", 1, lease);
    }

    @Test void unexpectedFailureKeepsDatabaseDetailsOutOfRetryAndTerminalMessages() throws Exception {
        for (int attempt : List.of(1, 3)) {
            var repository = mock(BackgroundJobRepository.class);
            var transitions = mock(JobStateTransitionService.class);
            var handler = mock(JobHandler.class);
            var job = new BackgroundJob(UUID.randomUUID(), "TEST", "lesson", UUID.randomUUID(),
                BackgroundJobStatus.RUNNING, 0, "TRANSCRIBING", 0, attempt, 3, null, "worker",
                Instant.now().plusSeconds(60), null, null, null, java.time.Instant.EPOCH, null, null);
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
