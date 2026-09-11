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

@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasRole('ADMIN')")
public class JobController {
    private final BackgroundJobService service;

    public JobController(BackgroundJobService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return service.findById(id)
            .map(job -> new JobResponse(
                job.id(),
                job.jobType(),
                job.status().name(),
                job.currentStep() == null ? "" : job.currentStep(),
                job.progressPercent(),
                job.attemptCount(),
                job.maxAttempts()
            ))
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
        int maxAttempts
    ) {}
}
