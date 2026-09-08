package com.lyreo.platform.web;

import com.lyreo.platform.jobs.application.BackgroundJobRepository;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.util.Map;
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
    public ResponseEntity<?> get(@PathVariable UUID id) {
        return repository.findById(id)
            .<ResponseEntity<?>>map(job -> ResponseEntity.ok(Map.of(
                "id", job.id(),
                "jobType", job.jobType(),
                "status", job.status(),
                "currentStep", job.currentStep() == null ? "" : job.currentStep(),
                "progressPercent", job.progressPercent(),
                "attemptCount", job.attemptCount(),
                "maxAttempts", job.maxAttempts()
            )))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        return service.cancel(id)
            ? ResponseEntity.accepted().build()
            : ResponseEntity.notFound().build();
    }
}
