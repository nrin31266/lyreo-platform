package com.lyreo.lesson.domain.authoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthoringLesson {
    public UUID id;
    public String title;
    public String videoId;
    public String sourceReference;
    public long startMs;
    public long endMs;
    public long revision;
    public long transcriptRevision;
    public Long approvedRevision;
    public Long karaokeReviewedRevision;
    public Long semanticRevision;
    public String status;
    public String timingStatus;
    public String audioObjectKey;
    public UUID activeJobId;
    public Instant publishedAt;
    public List<AuthoringSentence> sentences = List.of();
    public List<Job> jobs = List.of();
    public record Job(UUID jobId, String operation, String status, String currentStep,
                      int progressPercent, String errorMessage, Instant nextRetryAt,
                      List<Invocation> usage) {}
    public record Invocation(String provider, String model, Integer inputTokens,
                             Integer outputTokens, long latencyMs) {}
}
