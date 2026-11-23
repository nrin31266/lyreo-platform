package com.lyreo.speechassessment.application;

import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.application.CancellationResult;
import com.lyreo.platform.jobs.application.DeferredJobException;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.application.PermanentJobException;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminPronunciationAttempts implements JobHandler {
    public static final String JOB_TYPE = "ADMIN_PRONUNCIATION_ASSESSMENT";
    private static final String PROVIDER = "AZURE_SPEECH";
    private static final Logger log = LoggerFactory.getLogger(AdminPronunciationAttempts.class);
    private final Store store;
    private final ShadowingReference lessons;
    private final RecordingLimits limits;
    private final AudioPreparation audio;
    private final ObjectStoragePort storage;
    private final BackgroundJobService jobs;
    private final AiInvocationService ai;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public AdminPronunciationAttempts(Store store, ShadowingReference lessons, RecordingLimits limits,
        AudioPreparation audio, ObjectStoragePort storage, BackgroundJobService jobs,
        AiInvocationService ai, ObjectMapper mapper, TransactionTemplate transaction) {
        this.store = store;
        this.lessons = lessons;
        this.limits = limits;
        this.audio = audio;
        this.storage = storage;
        this.jobs = jobs;
        this.ai = ai;
        this.mapper = mapper;
        this.transaction = transaction;
    }

    public Constraints constraints(UUID lesson, UUID sentence, long revision) {
        var reference = lessons.read(lesson, sentence, revision, false);
        return new Constraints(reference.revision(), seconds(reference), limits.maxUploadBytes(),
            reference.startMs(), reference.endMs());
    }

    public int maxUploadBytes() {
        return limits.maxUploadBytes();
    }

    public Accepted submit(String subject, UUID key, UUID lesson, UUID sentence, long revision, byte[] upload) {
        if (upload.length == 0 || upload.length > limits.maxUploadBytes()) {
            throw new RequestValidationException("Recording size exceeds the allowed upload limits");
        }
        String fingerprint = fingerprint(lesson, sentence, revision, upload);
        var existing = store.byKey(subject, key, false);
        if (existing.isPresent()) {
            var previous = checkReplay(existing.get(), fingerprint);
            if (previous.jobId() != null) return accepted(previous);
        }
        var reference = lessons.read(lesson, sentence, revision, false);
        byte[] recording = audio.prepare(upload, seconds(reference));
        // Commit the reservation before writing the object so interrupted uploads can be reclaimed.
        store.reserve(UUID.randomUUID(), subject, key, fingerprint, lesson, sentence, revision);
        return transaction.execute(status -> {
            var attempt = checkReplay(store.byKey(subject, key, true)
                .orElseThrow(() -> new StateConflictException("Use a new submission key for this pronunciation attempt")), fingerprint);
            if (attempt.jobId() != null) return accepted(attempt);
            var current = lessons.read(lesson, sentence, revision, true);
            storage.put(objectKey(attempt.id()), "audio/wav", recording);
            UUID jobId = jobs.enqueue(JOB_TYPE, "speech-assessment", attempt.id(), 0, 3,
                "{\"provider\":\"AZURE_SPEECH\"}");
            return accepted(store.accept(attempt.id(), jobId, current));
        });
    }

    public View view(String subject, UUID id) {
        var attempt = owned(subject, id);
        var job = job(attempt);
        return new View(id, job.id(), attempt.lessonId(), attempt.sentenceId(), attempt.revision(),
            attempt.referenceText(), attempt.expiresAt(), job.status(), job.errorCode(),
            job.status() == BackgroundJobStatus.SUCCEEDED ? attempt.result() : null);
    }

    public byte[] recording(String subject, UUID id) throws IOException {
        owned(subject, id);
        return readRecording(id);
    }

    public void cancel(String subject, UUID id) {
        var attempt = owned(subject, id);
        if (jobs.cancel(attempt.jobId()) == CancellationResult.NOT_CANCELLABLE) {
            throw new StateConflictException("The pronunciation attempt has already finished");
        }
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job, JobExecutionContext context) throws IOException {
        var attempt = store.active(job.ownerReferenceId())
            .orElseThrow(() -> new PermanentJobException("ASSESSMENT_EXPIRED", "The pronunciation attempt expired"));
        if (attempt.result() != null) return;
        context.progress("ASSESSING", 10);
        byte[] recording = readRecording(attempt.id());
        context.throwIfCancellationRequested();
        var result = assess(job, attempt, recording);
        JsonNode output = mapper.valueToTree(result.output());
        if (output.path("schemaVersion").asInt() != 1 || !output.path("words").isArray()
            || !output.path("scores").isObject()
            || !List.of("SCORED", "NO_SPEECH").contains(output.path("outcome").asText())) {
            throw new PermanentJobException("ASSESSMENT_INVALID_RESULT", "Pronunciation assessment returned an invalid result");
        }
        context.throwIfCancellationRequested();
        transaction.executeWithoutResult(status -> {
            jobs.fenceForCommit(job.id(), job.leaseOwner());
            if (!store.complete(attempt.id(), result.invocationId(), output)) {
                throw new PermanentJobException("ASSESSMENT_EXPIRED", "The pronunciation attempt expired");
            }
        });
    }

    private AiExecutionResult assess(BackgroundJob job, Attempt attempt, byte[] recording) {
        try {
            return ai.executeForProvider(AiCapability.PRONUNCIATION_ASSESSMENT, PROVIDER, "",
                Map.of("referenceText", attempt.referenceText(), "audio", recording), Map.of());
        } catch (AiExecutionException failure) {
            if (failure.retryAfterSeconds() > 0) {
                if (job.attemptCount() < job.maxAttempts()) {
                    throw new DeferredJobException("Azure Speech is busy. Waiting to retry",
                        Instant.now().plusSeconds(failure.retryAfterSeconds()));
                }
                throw new PermanentJobException("ASSESSMENT_QUOTA", "Azure Speech is busy or its quota is exhausted");
            }
            if (failure.fallbackAllowed() && job.attemptCount() < job.maxAttempts()) {
                throw new IllegalStateException("Temporary Azure Speech assessment failure");
            }
            boolean configuration = List.of("AI_ROUTE_NOT_CONFIGURED", "AI_FREE_ONLY_ROUTE_BLOCKED",
                "AI_HTTP_401", "AI_HTTP_403").contains(failure.errorCode());
            throw configuration
                ? new PermanentJobException("ASSESSMENT_CONFIGURATION", "Check the Azure Speech route and credential")
                : new PermanentJobException("ASSESSMENT_PROVIDER_FAILED", "Azure Speech could not assess the recording");
        }
    }

    @Scheduled(fixedDelayString = "${lyreo.speech-assessment.purge-interval-ms:300000}")
    public void purgeExpired() {
        for (UUID id : store.expired()) {
            try {
                transaction.executeWithoutResult(status -> {
                    if (store.lockExpired(id)) {
                        storage.delete(objectKey(id));
                        store.delete(id);
                    }
                });
            } catch (RuntimeException failure) {
                log.warn("Could not remove expired pronunciation attempt {}", id);
            }
        }
    }

    private Attempt owned(String subject, UUID id) {
        return store.active(id).filter(attempt -> subject.equals(attempt.adminSubject()))
            .orElseThrow(() -> new ResourceNotFoundException("Pronunciation attempt not found"));
    }

    private BackgroundJob job(Attempt attempt) {
        return jobs.findById(attempt.jobId())
            .orElseThrow(() -> new ResourceNotFoundException("Pronunciation assessment job not found"));
    }

    private byte[] readRecording(UUID attemptId) throws IOException {
        try (var stream = storage.get(objectKey(attemptId))) {
            return stream.readAllBytes();
        }
    }

    private int seconds(ShadowingReference.Sentence reference) {
        return limits.secondsFor(reference.endMs() - reference.startMs());
    }

    private static Attempt checkReplay(Attempt attempt, String fingerprint) {
        if (!attempt.fingerprint().equals(fingerprint) || !attempt.expiresAt().isAfter(Instant.now())) {
            throw new StateConflictException("Use a new submission key for this pronunciation attempt");
        }
        return attempt;
    }

    private static Accepted accepted(Attempt attempt) {
        return new Accepted(attempt.id(), attempt.jobId(), attempt.expiresAt());
    }

    private static String fingerprint(UUID lesson, UUID sentence, long revision, byte[] audio) {
        var digest = sha256();
        digest.update((lesson + "/" + sentence + "/" + revision + "/").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest(audio));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String objectKey(UUID id) {
        return "admin-pronunciation-attempts/" + id + "/recording.wav";
    }

    public record Constraints(long revision, int maxDurationSeconds, int maxUploadBytes, long startMs, long endMs) {}
    public record Accepted(UUID attemptId, UUID jobId, Instant expiresAt) {}
    public record View(UUID attemptId, UUID jobId, UUID lessonId, UUID sentenceId, long revision,
                       String referenceText, Instant expiresAt, BackgroundJobStatus status, String errorCode, JsonNode result) {}
    public record Attempt(UUID id, String adminSubject, String fingerprint, UUID lessonId, UUID sentenceId,
                          long revision, UUID jobId, String referenceText, Instant expiresAt, JsonNode result) {}

    public interface Store {
        void reserve(UUID id, String subject, UUID key, String fingerprint, UUID lesson, UUID sentence, long revision);
        Optional<Attempt> byKey(String subject, UUID key, boolean lock);
        Optional<Attempt> active(UUID id);
        Attempt accept(UUID id, UUID job, ShadowingReference.Sentence reference);
        boolean complete(UUID id, UUID invocationId, JsonNode result);
        List<UUID> expired();
        boolean lockExpired(UUID id);
        void delete(UUID id);
    }

    public interface AudioPreparation {
        byte[] prepare(byte[] audio, int limitSeconds);
    }
}
