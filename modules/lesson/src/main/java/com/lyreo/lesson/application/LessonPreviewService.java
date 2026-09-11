package com.lyreo.lesson.application;

import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application service orchestrating lesson preview with cross-module runtime job status. */
public final class LessonPreviewService {
    private final LessonPreviewQuery query;
    private final BackgroundJobService jobs;

    public LessonPreviewService(LessonPreviewQuery query, BackgroundJobService jobs) {
        this.query = query;
        this.jobs = jobs;
    }

    public Optional<LessonPreviewView> find(UUID lessonId) {
        return query.find(lessonId).map(this::enrichBuildJobs);
    }

    private LessonPreviewView enrichBuildJobs(LessonPreviewView base) {
        if (base.buildJobs() == null || base.buildJobs().isEmpty()) {
            return base;
        }
        List<LessonPreviewView.BuildJobView> enrichedJobs = new ArrayList<>(base.buildJobs().size());
        for (LessonPreviewView.BuildJobView link : base.buildJobs()) {
            Optional<BackgroundJob> jobOpt = jobs.findById(link.jobId());
            enrichedJobs.add(new LessonPreviewView.BuildJobView(
                link.jobId(),
                link.createdAt(),
                jobOpt.map(j -> j.status().name()).orElse(null),
                jobOpt.map(BackgroundJob::currentStep).orElse(null),
                jobOpt.map(BackgroundJob::progressPercent).orElse(null),
                jobOpt.map(BackgroundJob::attemptCount).orElse(null),
                jobOpt.map(BackgroundJob::errorMessage).orElse(null)
            ));
        }
        return new LessonPreviewView(
            base.id(),
            base.title(),
            base.sourceType(),
            base.sourceText(),
            base.sourceReference(),
            base.canonicalAudioObjectKey(),
            base.status(),
            base.createdAt(),
            base.updatedAt(),
            base.sentences(),
            base.activities(),
            enrichedJobs
        );
    }
}
