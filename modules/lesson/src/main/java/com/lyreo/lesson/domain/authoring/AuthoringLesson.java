package com.lyreo.lesson.domain.authoring;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuthoringLesson {
    public boolean autoContinue;
    public UUID publishedBy;
    public AuthoringOptions options = AuthoringOptions.defaults();
    public AuthoringProfile profile = new AuthoringProfile();
    public String sourceType = "YOUTUBE";
    public UUID audioSourceId;
    public String ttsVoice;
    public UUID id;
    public String title;
    public boolean automaticTitle;
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
    public String timingStatus = "MISSING";
    public boolean alignmentRequired;
    public String audioObjectKey;
    public java.util.Map<String,Object> timingEvidence = java.util.Map.of();
    public UUID activeJobId;
    public Instant publishedAt;
    public List<AuthoringSentence> sentences = List.of();
    public List<Job> jobs = List.of();
    public record Job(UUID jobId, String operation, String status, String currentStep,
                      int progressPercent, String errorMessage, String errorCode, Instant nextRetryAt,
                      List<Invocation> usage, Instant createdAt, Instant startedAt, Instant finishedAt,
                      int attemptCount, AuthoringJobResult result) {}
    public record Invocation(String provider, String model, Integer inputTokens,
                             Integer outputTokens, long latencyMs, Double audioSeconds,
                             UUID invocationId, String capability, String status, Boolean fallback) {
        public Invocation(String provider,String model,Integer inputTokens,Integer outputTokens,long latencyMs,Double audioSeconds) {
            this(provider,model,inputTokens,outputTokens,latencyMs,audioSeconds,null,null,null,null);
        }
        public Invocation(String provider,String model,Integer inputTokens,Integer outputTokens,long latencyMs) {
            this(provider,model,inputTokens,outputTokens,latencyMs,null);
        }
    }
}
