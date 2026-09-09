package com.lyreo.platform.web;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.platform.jobs.application.BackgroundJobRepository;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@PreAuthorize("hasRole('ADMIN')")
public class JobController {
    private final BackgroundJobRepository repository;
    private final BackgroundJobService service;

    public JobController(BackgroundJobRepository repository, BackgroundJobService service) {
        this.repository = repository;
        this.service = service;
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return repository.findById(id)
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
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        if (!service.cancel(id)) {
            throw new ResourceNotFoundException("Background job not found: " + id);
        }
        return ResponseEntity.accepted().build();
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
