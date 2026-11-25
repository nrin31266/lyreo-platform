package com.lyreo.speechassessment.application;

import com.lyreo.contracts.errors.IdempotencyConflictException;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureAccess;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.api.QuotaReservation;
import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.CancellationResult;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Learner-facing Shadowing pronunciation attempt lifecycle and 24-hour audio retention. */
@Service
public class LearnerPronunciationAttempts {
    public static final String JOB_TYPE = "LEARNER_PRONUNCIATION_ASSESSMENT";
    public static final FeatureKey SHADOWING_FEATURE = FeatureKey.of("speech.shadowing");
    private static final Logger log = LoggerFactory.getLogger(LearnerPronunciationAttempts.class);

    private final Store store;
    private final ShadowingReference lessons;
    private final RecordingLimits limits;
    private final AdminPronunciationAttempts.AudioPreparation audio;
    private final ObjectStoragePort storage;
    private final BackgroundJobService jobs;
    private final EntitlementService entitlements;
    private final TransactionTemplate transaction;

    public LearnerPronunciationAttempts(
        Store store,
        ShadowingReference lessons,
        RecordingLimits limits,
        AdminPronunciationAttempts.AudioPreparation audio,
        ObjectStoragePort storage,
        BackgroundJobService jobs,
        EntitlementService entitlements,
        TransactionTemplate transaction
    ) {
        this.store = store;
        this.lessons = lessons;
        this.limits = limits;
        this.audio = audio;
        this.storage = storage;
        this.jobs = jobs;
        this.entitlements = entitlements;
        this.transaction = transaction;
    }

    public Constraints constraints(UUID learnerId, UUID lessonId, UUID activityId, UUID sentenceId, long revision) {
        var reference = lessons.readForLearner(lessonId, activityId, sentenceId, revision, false);
        FeatureAccess quota = entitlements.evaluateFeature(learnerId, SHADOWING_FEATURE);
        return new Constraints(
            reference.revision(),
            seconds(reference),
            limits.maxUploadBytes(),
            reference.startMs(),
            reference.endMs(),
            quota.granted(),
            quota.tier(),
            quota.limit(),
            quota.remaining(),
            quota.resetAt(),
            quota.lockReason()
        );
    }

    public int maxUploadBytes() {
        return limits.maxUploadBytes();
    }

    public Accepted submit(
        UUID learnerId,
        UUID key,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision,
        byte[] upload
    ) {
        if (upload == null || upload.length == 0 || upload.length > limits.maxUploadBytes()) {
            throw new RequestValidationException("Recording size exceeds the allowed upload limits");
        }
        String fingerprint = fingerprint(lessonId, activityId, sentenceId, revision, upload);

        var existing = store.byKey(learnerId, key, false);
        if (existing.isPresent()) {
            var previous = checkReplay(existing.get(), key, fingerprint);
            if (previous.jobId() != null) {
                return accepted(previous, null);
            }
        }

        var reference = lessons.readForLearner(lessonId, activityId, sentenceId, revision, false);
        byte[] recording = audio.prepare(upload, seconds(reference));

        store.reserve(UUID.randomUUID(), learnerId, key, fingerprint, lessonId, activityId, sentenceId, revision);
        return transaction.execute(status -> {
            var attempt = checkReplay(
                store.byKey(learnerId, key, true)
                    .orElseThrow(() -> new StateConflictException("Use a new submission key for this pronunciation attempt")),
                key,
                fingerprint
            );
            if (attempt.jobId() != null) {
                return accepted(attempt, null);
            }
            var current = lessons.readForLearner(lessonId, activityId, sentenceId, revision, true);
            QuotaReservation reservation = entitlements.reserveQuota(
                learnerId,
                SHADOWING_FEATURE,
                "shadowing:" + attempt.id()
            );

            String objectKey = objectKey(attempt.id());
            storage.put(objectKey, "audio/wav", recording);
            try {
                UUID jobId = jobs.enqueue(
                    JOB_TYPE,
                    "speech-assessment",
                    attempt.id(),
                    0,
                    3,
                    "{\"capability\":\"PRONUNCIATION_ASSESSMENT\"}"
                );
                var saved = store.accept(attempt.id(), jobId, reservation.reservationId(), objectKey, current);
                return accepted(saved, reservation);
            } catch (RuntimeException failure) {
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        });
    }

    public View view(UUID learnerId, UUID attemptId) {
        var attempt = owned(learnerId, attemptId);
        var job = job(attempt);
        boolean audioAvailable = attempt.recordingPurgedAt() == null
            && attempt.recordingObjectKey() != null
            && attempt.expiresAt().isAfter(Instant.now());
        return new View(
            attempt.id(),
            job.id(),
            attempt.lessonId(),
            attempt.activityId(),
            attempt.sentenceId(),
            attempt.revision(),
            attempt.referenceText(),
            attempt.expiresAt(),
            audioAvailable,
            attempt.recordingPurgedAt(),
            job.status(),
            job.errorCode(),
            attempt.outcome(),
            attempt.overallScore(),
            attempt.result(),
            attempt.createdAt()
        );
    }

    public byte[] recording(UUID learnerId, UUID attemptId) throws IOException {
        var attempt = owned(learnerId, attemptId);
        if (attempt.recordingPurgedAt() != null
            || attempt.recordingObjectKey() == null
            || !attempt.expiresAt().isAfter(Instant.now())) {
            throw new ResourceNotFoundException("Pronunciation recording expired");
        }
        try (var stream = storage.get(attempt.recordingObjectKey())) {
            return stream.readAllBytes();
        }
    }

    public void cancel(UUID learnerId, UUID attemptId) {
        var attempt = owned(learnerId, attemptId);
        if (jobs.cancel(attempt.jobId()) == CancellationResult.NOT_CANCELLABLE) {
            throw new StateConflictException("The pronunciation attempt has already finished");
        }
        var current = jobs.findById(attempt.jobId()).orElse(null);
        if (current != null && current.status() == BackgroundJobStatus.CANCELLED && attempt.reservationId() != null) {
            entitlements.releaseQuota(attempt.reservationId(), "CANCELLED");
        }
    }

    @Scheduled(fixedDelayString = "${lyreo.speech-assessment.purge-interval-ms:300000}")
    public void purgeExpired() {
        for (UUID id : store.expiredAudioIds()) {
            try {
                transaction.executeWithoutResult(status -> {
                    store.lockForPurge(id).ifPresent(attempt -> {
                        if (attempt.recordingObjectKey() != null) {
                            storage.delete(attempt.recordingObjectKey());
                        }
                        if (attempt.jobId() == null) {
                            if (attempt.reservationId() != null) {
                                entitlements.releaseQuota(attempt.reservationId(), "EXPIRED");
                            }
                            store.delete(id);
                        } else {
                            if (attempt.reservationId() != null && attempt.outcome() == null) {
                                var job = jobs.findById(attempt.jobId()).orElse(null);
                                if (job == null || job.status() == BackgroundJobStatus.FAILED
                                    || job.status() == BackgroundJobStatus.CANCELLED) {
                                    entitlements.releaseQuota(attempt.reservationId(), "EXPIRED");
                                }
                            }
                            store.markRecordingPurged(id);
                        }
                    });
                });
            } catch (RuntimeException failure) {
                log.warn("Could not purge expired learner pronunciation audio {}", id);
            }
        }
    }

    private Attempt owned(UUID learnerId, UUID attemptId) {
        return store.byId(attemptId)
            .filter(attempt -> attempt.jobId() != null && learnerId.equals(attempt.learnerId()))
            .orElseThrow(() -> new ResourceNotFoundException("Pronunciation attempt not found"));
    }

    private BackgroundJob job(Attempt attempt) {
        return jobs.findById(attempt.jobId())
            .orElseThrow(() -> new ResourceNotFoundException("Pronunciation assessment job not found"));
    }

    private int seconds(ShadowingReference.LearnerSentence reference) {
        return limits.secondsFor(reference.endMs() - reference.startMs());
    }

    private static Attempt checkReplay(Attempt attempt, UUID key, String fingerprint) {
        if (!attempt.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(
                "Idempotency-Key was already used with a different request payload.",
                key.toString()
            );
        }
        if (!attempt.expiresAt().isAfter(Instant.now())) {
            throw new StateConflictException("Use a new submission key for this pronunciation attempt");
        }
        return attempt;
    }

    private static Accepted accepted(Attempt attempt, QuotaReservation reservation) {
        return new Accepted(
            attempt.id(),
            attempt.jobId(),
            attempt.expiresAt(),
            reservation == null ? null : reservation.limit(),
            reservation == null ? null : reservation.remaining(),
            reservation == null ? null : reservation.resetAt()
        );
    }

    private static String fingerprint(UUID lessonId, UUID activityId, UUID sentenceId, long revision, byte[] audio) {
        var digest = sha256();
        digest.update((lessonId + "/" + activityId + "/" + sentenceId + "/" + revision + "/")
            .getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(audio));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String objectKey(UUID attemptId) {
        return "learner-pronunciation-attempts/" + attemptId + "/recording.wav";
    }

    public record Constraints(
        long revision,
        int maxDurationSeconds,
        int maxUploadBytes,
        long startMs,
        long endMs,
        boolean quotaAllowed,
        String tier,
        Integer quotaLimit,
        Integer quotaRemaining,
        Instant quotaResetAt,
        String lockReason
    ) {}

    public record Accepted(
        UUID attemptId,
        UUID jobId,
        Instant expiresAt,
        Integer quotaLimit,
        Integer quotaRemaining,
        Instant quotaResetAt
    ) {}

    public record View(
        UUID attemptId,
        UUID jobId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision,
        String referenceText,
        Instant expiresAt,
        boolean audioAvailable,
        Instant recordingPurgedAt,
        BackgroundJobStatus status,
        String errorCode,
        String outcome,
        Integer overallScore,
        JsonNode result,
        Instant createdAt
    ) {}

    public record Attempt(
        UUID id,
        UUID learnerId,
        String fingerprint,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision,
        UUID jobId,
        UUID reservationId,
        String recordingObjectKey,
        Instant recordingPurgedAt,
        Instant expiresAt,
        String referenceText,
        String outcome,
        Integer overallScore,
        JsonNode result,
        Instant createdAt
    ) {}

    public interface Store {
        void reserve(
            UUID id,
            UUID learnerId,
            UUID key,
            String fingerprint,
            UUID lessonId,
            UUID activityId,
            UUID sentenceId,
            long revision
        );

        Optional<Attempt> byKey(UUID learnerId, UUID key, boolean lock);

        Optional<Attempt> byId(UUID id);

        Attempt accept(
            UUID id,
            UUID jobId,
            UUID reservationId,
            String recordingObjectKey,
            ShadowingReference.LearnerSentence reference
        );

        boolean complete(UUID id, UUID invocationId, String outcome, Integer overallScore, JsonNode result);

        Optional<UUID> findReservationIdByJobId(UUID jobId);

        List<UUID> expiredAudioIds();

        Optional<Attempt> lockForPurge(UUID id);

        void markRecordingPurged(UUID id);

        void delete(UUID id);
    }
}
