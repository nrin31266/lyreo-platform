package com.lyreo.speechassessment.application;

import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.contracts.jobs.JobProgressChangedEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.lesson.api.LearnerShadowingCompletionPort;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.DeferredJobException;
import com.lyreo.platform.jobs.application.JobCancelledException;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.application.PermanentJobException;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts.Attempt;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts.Store;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable background worker for learner Shadowing pronunciation assessment.
 *
 * <p>Runs {@link AiInvocationService#execute} outside any database transaction, then settles
 * quota and advances lesson completion atomically on {@code SCORED}, or releases quota cleanly on
 * {@code NO_SPEECH}, cancellation, or provider failure.</p>
 */
@Component
public class LearnerPronunciationJobHandler implements JobHandler {
    private final Store store;
    private final ObjectStoragePort storage;
    private final BackgroundJobService jobs;
    private final AiInvocationService ai;
    private final EntitlementService entitlements;
    private final LearnerShadowingCompletionPort shadowingCompletion;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public LearnerPronunciationJobHandler(
        Store store,
        ObjectStoragePort storage,
        BackgroundJobService jobs,
        AiInvocationService ai,
        EntitlementService entitlements,
        LearnerShadowingCompletionPort shadowingCompletion,
        ObjectMapper mapper,
        TransactionTemplate transaction
    ) {
        this.store = store;
        this.storage = storage;
        this.jobs = jobs;
        this.ai = ai;
        this.entitlements = entitlements;
        this.shadowingCompletion = shadowingCompletion;
        this.mapper = mapper;
        this.transaction = transaction;
    }

    @Override
    public String jobType() {
        return LearnerPronunciationAttempts.JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job, JobExecutionContext context) throws IOException {
        Attempt attempt = store.byId(job.ownerReferenceId())
            .filter(row -> row.jobId() != null)
            .orElseThrow(() -> new PermanentJobException("ASSESSMENT_EXPIRED", "The pronunciation attempt expired"));

        if (attempt.result() != null) {
            return;
        }
        if (!attempt.expiresAt().isAfter(Instant.now())
            || attempt.recordingPurgedAt() != null
            || attempt.recordingObjectKey() == null) {
            releaseQuotaSafely(attempt, "EXPIRED");
            throw new PermanentJobException("ASSESSMENT_EXPIRED", "The pronunciation attempt expired");
        }

        try {
            context.progress("ASSESSING", 10);
            byte[] recording = readRecording(attempt.recordingObjectKey());
            context.throwIfCancellationRequested();

            AiExecutionResult result = assess(job, attempt, recording);
            JsonNode output = mapper.valueToTree(result.output());
            String outcome = output.path("outcome").asText("");
            if (output.path("schemaVersion").asInt() != 1
                || !output.path("words").isArray()
                || !output.path("scores").isObject()
                || !List.of("SCORED", "NO_SPEECH").contains(outcome)) {
                releaseQuotaSafely(attempt, "ASSESSMENT_INVALID_RESULT");
                throw new PermanentJobException(
                    "ASSESSMENT_INVALID_RESULT",
                    "Pronunciation assessment returned an invalid result"
                );
            }

            Integer overallScore = null;
            if ("SCORED".equals(outcome)) {
                if (!output.path("scores").path("overall").isNumber()) {
                    releaseQuotaSafely(attempt, "ASSESSMENT_INVALID_RESULT");
                    throw new PermanentJobException(
                        "ASSESSMENT_INVALID_RESULT",
                        "Pronunciation assessment returned an invalid overall score"
                    );
                }
                overallScore = Math.clamp(Math.round((float) output.path("scores").path("overall").asDouble()), 0, 100);
            }

            context.throwIfCancellationRequested();
            final Integer finalScore = overallScore;
            transaction.executeWithoutResult(status -> {
                jobs.fenceForCommit(job.id(), job.leaseOwner());
                if (!store.complete(attempt.id(), result.invocationId(), outcome, finalScore, output)) {
                    throw new PermanentJobException("ASSESSMENT_EXPIRED", "The pronunciation attempt expired");
                }
                if ("SCORED".equals(outcome)) {
                    if (attempt.reservationId() != null) {
                        entitlements.settleQuota(attempt.reservationId());
                    }
                    String transcript = output.path("transcript").asText("");
                    shadowingCompletion.recordScoredAttempt(
                        attempt.learnerId(),
                        attempt.lessonId(),
                        attempt.activityId(),
                        attempt.sentenceId(),
                        attempt.id(),
                        finalScore,
                        transcript,
                        mapper.writeValueAsString(output)
                    );
                } else {
                    releaseQuotaSafely(attempt, "NO_SPEECH");
                }
            });
        } catch (JobCancelledException cancelled) {
            releaseQuotaSafely(attempt, "CANCELLED");
            throw cancelled;
        }
    }

    @ApplicationModuleListener
    public void onJobProgress(JobProgressChangedEvent event) {
        if (!LearnerPronunciationAttempts.JOB_TYPE.equals(event.jobType())) {
            return;
        }
        if ("CANCELLED".equals(event.status()) || "FAILED".equals(event.status())) {
            store.findReservationIdByJobId(event.jobId())
                .ifPresent(reservationId -> entitlements.releaseQuota(reservationId, event.status()));
        }
    }

    private AiExecutionResult assess(BackgroundJob job, Attempt attempt, byte[] recording) {
        try {
            return ai.execute(
                AiCapability.PRONUNCIATION_ASSESSMENT,
                "",
                Map.of("referenceText", attempt.referenceText(), "audio", recording),
                Map.of()
            );
        } catch (AiExecutionException failure) {
            if (failure.retryAfterSeconds() > 0) {
                if (job.attemptCount() < job.maxAttempts()) {
                    throw new DeferredJobException(
                        "Pronunciation assessment provider is busy. Waiting to retry",
                        Instant.now().plusSeconds(failure.retryAfterSeconds())
                    );
                }
                releaseQuotaSafely(attempt, "ASSESSMENT_QUOTA");
                throw new PermanentJobException(
                    "ASSESSMENT_QUOTA",
                    "Pronunciation assessment provider is busy or its quota is exhausted"
                );
            }
            if (failure.fallbackAllowed() && job.attemptCount() < job.maxAttempts()) {
                throw new IllegalStateException("Temporary pronunciation assessment failure", failure);
            }
            boolean configuration = List.of(
                "AI_ROUTE_NOT_CONFIGURED",
                "AI_FREE_ONLY_ROUTE_BLOCKED",
                "AI_HTTP_401",
                "AI_HTTP_403"
            ).contains(failure.errorCode());
            String code = configuration ? "ASSESSMENT_CONFIGURATION" : "ASSESSMENT_PROVIDER_FAILED";
            releaseQuotaSafely(attempt, code);
            throw configuration
                ? new PermanentJobException(code, "Check the pronunciation assessment route and credential")
                : new PermanentJobException(code, "Pronunciation assessment provider could not assess the recording");
        }
    }

    private byte[] readRecording(String objectKey) throws IOException {
        try (var stream = storage.get(objectKey)) {
            return stream.readAllBytes();
        }
    }

    private void releaseQuotaSafely(Attempt attempt, String reason) {
        if (attempt.reservationId() != null) {
            entitlements.releaseQuota(attempt.reservationId(), reason);
        }
    }
}
