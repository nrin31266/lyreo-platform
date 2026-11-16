package com.lyreo.platform.web;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.CancellationResult;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.lyreo.platform.jobs.domain.BackgroundJob;

@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasRole('ADMIN')")
public class JobController {
    private final BackgroundJobService service;

    public JobController(BackgroundJobService service) {
        this.service = service;
    }

    @GetMapping
    public JobPage list(@RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String type,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var result = service.list(status, type, page, size);
        return new JobPage(result.items().stream().map(JobController::response).toList(), result.total(), result.page(), result.size());
    }

    public record JobPage(java.util.List<JobResponse> items, long total, int page, int size) {}

    private static JobResponse response(BackgroundJob job) {
        return new JobResponse(job.id(), job.jobType(), job.status().name(), job.currentStep() == null ? "" : job.currentStep(),
            job.progressPercent(), job.attemptCount(), job.maxAttempts(), job.createdAt(), job.startedAt(), job.finishedAt(),
            job.nextRetryAt(), job.errorCode(), safeError(job.errorMessage()), job.ownerModule(), job.ownerReferenceId());
    }

    private static String safeError(String message) {
        return message == null ? null : message.replaceAll("https?://[^\\s\\\"<>]+", "[URL removed]")
            .replaceAll("(?i)(bearer|api[_ -]?key|password|secret|access[_ -]?token)\\s*[:= ]\\s*\\S+", "$1 [redacted]");
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.findById(id)
            .map(JobController::response)
            .orElseThrow(() -> new ResourceNotFoundException("Background job not found: " + id));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        CancellationResult result = service.cancel(id);
        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted().build();
            case NOT_FOUND -> throw new ResourceNotFoundException("Background job not found: " + id);
            case NOT_CANCELLABLE -> throw new StateConflictException("Background job " + id + " is not cancellable in its current state");
        };
    }

    public record JobResponse(
        UUID id,
        String jobType,
        String status,
        String currentStep,
        int progressPercent,
        int attemptCount,
        int maxAttempts,
        java.time.Instant createdAt,
        java.time.Instant startedAt,
        java.time.Instant finishedAt,
        java.time.Instant nextRetryAt,
        String errorCode,
        String errorMessage,
        String ownerModule,
        UUID ownerReferenceId
    ) {}
}
