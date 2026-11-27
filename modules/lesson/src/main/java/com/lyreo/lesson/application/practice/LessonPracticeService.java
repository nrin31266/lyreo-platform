package com.lyreo.lesson.application.practice;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.lesson.LessonSentenceProgressEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.lesson.application.port.LessonPracticeRepository;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Learner Lesson practice application service.
 *
 * <p>The client submits evidence (for Dictation: answer text), never a trusted score or reward.
 * The server computes score, appends an attempt, projects progress and publishes completion events
 * in one short transaction.</p>
 */
public class LessonPracticeService {
    static final int DEFAULT_COMPLETION_SCORE = 70;
    private static final String DICTATION_OPERATION = "LESSON_DICTATION_ATTEMPT";
    private static final String WATCH_OPERATION = "LESSON_WATCH_COMPLETE";

    private final LessonPracticeRepository repository;
    private final DictationScoringPolicy dictationScoring;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final OperationIdempotencyService idempotency;
    private final EntitlementService entitlements;

    public LessonPracticeService(
        LessonPracticeRepository repository,
        DictationScoringPolicy dictationScoring,
        ApplicationEventPublisher events,
        ObjectMapper mapper
    ) {
        this(repository, dictationScoring, events, mapper, null, null);
    }

    public LessonPracticeService(
        LessonPracticeRepository repository,
        DictationScoringPolicy dictationScoring,
        ApplicationEventPublisher events,
        ObjectMapper mapper,
        OperationIdempotencyService idempotency
    ) {
        this(repository, dictationScoring, events, mapper, idempotency, null);
    }

    public LessonPracticeService(
        LessonPracticeRepository repository,
        DictationScoringPolicy dictationScoring,
        ApplicationEventPublisher events,
        ObjectMapper mapper,
        OperationIdempotencyService idempotency,
        EntitlementService entitlements
    ) {
        this.repository = repository;
        this.dictationScoring = dictationScoring;
        this.events = events;
        this.mapper = mapper;
        this.idempotency = idempotency;
        this.entitlements = entitlements;
    }

    @Transactional
    public DictationAttemptResult submitDictation(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String answer
    ) {
        enforceLessonAccess(learnerId, lessonId);
        return executeDictation(learnerId, lessonId, activityId, sentenceId, answer);
    }

    @Transactional
    public DictationAttemptResult submitDictation(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String answer,
        String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        enforceLessonAccess(learnerId, lessonId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotency == null) {
            return executeDictation(learnerId, lessonId, activityId, sentenceId, answer);
        }
        String fingerprint = lessonId + ":" + activityId + ":" + sentenceId + ":" + (answer == null ? "" : answer);
        return idempotency.execute(
            learnerId,
            DICTATION_OPERATION,
            idempotencyKey,
            fingerprint,
            DictationAttemptResult.class,
            () -> executeDictation(learnerId, lessonId, activityId, sentenceId, answer)
        );
    }

    @Transactional
    public WatchCompletionResult completeWatch(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);
        enforceLessonAccess(learnerId, lessonId);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotency == null) {
            return executeCompleteWatch(learnerId, lessonId, activityId);
        }
        String fingerprint = lessonId + ":" + activityId + ":WATCH";
        return idempotency.execute(
            learnerId,
            WATCH_OPERATION,
            idempotencyKey,
            fingerprint,
            WatchCompletionResult.class,
            () -> executeCompleteWatch(learnerId, lessonId, activityId)
        );
    }

    private void enforceLessonAccess(UUID learnerId, UUID lessonId) {
        if (entitlements == null || learnerId == null || lessonId == null) {
            return;
        }
        repository.findLessonAccess(lessonId).ifPresent(access -> {
            if ("FEATURE".equals(access.accessMode())
                && access.requiredFeatureKey() != null
                && !access.requiredFeatureKey().isBlank()) {
                entitlements.requireFeature(learnerId, FeatureKey.of(access.requiredFeatureKey()));
            }
        });
    }

    private DictationAttemptResult executeDictation(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String answer
    ) {
        if (answer == null) {
            throw new RequestValidationException("answer is required");
        }

        var target = repository.findDictationTarget(lessonId, activityId, sentenceId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Dictation activity/sentence does not belong to this lesson"
            ));

        var score = dictationScoring.score(target.expectedText(), answer);
        String detailJson = json(Map.of(
            "policy", "RELAXED_TOKEN_DISTANCE_V1",
            "expectedTokenCount", score.expectedTokenCount(),
            "answerTokenCount", score.answerTokenCount()
        ));

        UUID attemptId = repository.appendDictationAttempt(
            learnerId,
            target,
            answer,
            score.percent(),
            detailJson
        );

        var progress = repository.refreshActivityProgress(
            learnerId,
            lessonId,
            activityId,
            DEFAULT_COMPLETION_SCORE
        );

        boolean activityCompleted = repository.tryMarkActivityCompleted(learnerId, activityId);
        Instant occurredAt = Instant.now();
        boolean sentencePassed = score.percent() >= DEFAULT_COMPLETION_SCORE;
        List<UUID> completedSentenceIds = (activityCompleted || sentencePassed)
            ? repository.findCompletedSentenceIds(learnerId, activityId, DEFAULT_COMPLETION_SCORE)
            : List.of();
        if (sentencePassed) {
            events.publishEvent(new LessonSentenceProgressEvent(
                attemptId,
                learnerId,
                lessonId,
                activityId,
                "DICTATION",
                sentenceId,
                score.percent(),
                completedSentenceIds,
                occurredAt
            ));
        }
        if (activityCompleted) {
            events.publishEvent(new LessonActivityCompletedEvent(
                activityFactId(learnerId, activityId),
                learnerId,
                lessonId,
                activityId,
                "DICTATION",
                progress.bestScore() == null ? score.percent() : progress.bestScore(),
                occurredAt,
                completedSentenceIds
            ));
        }

        boolean lessonCompleted = repository.tryMarkLessonCompleted(learnerId, lessonId);
        if (lessonCompleted) {
            events.publishEvent(new LessonCompletedEvent(
                lessonFactId(learnerId, lessonId),
                learnerId,
                lessonId,
                occurredAt
            ));
        }

        return new DictationAttemptResult(
            attemptId,
            score.percent(),
            target.expectedText(),
            progress.completedItems(),
            progress.totalItems(),
            activityCompleted,
            lessonCompleted
        );
    }

    private WatchCompletionResult executeCompleteWatch(
        UUID learnerId,
        UUID lessonId,
        UUID activityId
    ) {
        var target = repository.findWatchTarget(lessonId, activityId)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson activity not found"));
        if (!"WATCH".equals(target.activityType())) {
            throw new RequestValidationException("Only WATCH activities can be completed explicitly");
        }

        var completion = repository.completeWatchActivity(
            learnerId,
            lessonId,
            activityId,
            target.totalItems()
        );
        Instant occurredAt = Instant.now();
        if (completion.newlyCompleted()) {
            List<UUID> sentenceIds = repository.findActivitySentenceIds(lessonId, activityId);
            events.publishEvent(new LessonActivityCompletedEvent(
                activityFactId(learnerId, activityId),
                learnerId,
                lessonId,
                activityId,
                "WATCH",
                100,
                occurredAt,
                sentenceIds
            ));
        }

        boolean lessonCompleted = repository.tryMarkLessonCompleted(learnerId, lessonId);
        if (lessonCompleted) {
            events.publishEvent(new LessonCompletedEvent(
                lessonFactId(learnerId, lessonId),
                learnerId,
                lessonId,
                occurredAt
            ));
        }

        return new WatchCompletionResult(
            activityId,
            "COMPLETED",
            completion.completedItems(),
            completion.totalItems(),
            completion.newlyCompleted(),
            lessonCompleted
        );
    }

    static UUID activityFactId(UUID learnerId, UUID activityId) {
        return UUID.nameUUIDFromBytes(
            ("lesson-activity:" + learnerId + ":" + activityId).getBytes(StandardCharsets.UTF_8)
        );
    }

    static UUID lessonFactId(UUID learnerId, UUID lessonId) {
        return UUID.nameUUIDFromBytes(
            ("lesson:" + learnerId + ":" + lessonId).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.trim().length() > 128) {
            throw new RequestValidationException("Idempotency-Key must be at most 128 characters");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize attempt detail", exception);
        }
    }

    public record DictationAttemptResult(
        UUID attemptId,
        int serverScore,
        String expectedText,
        int completedItems,
        int totalItems,
        boolean activityCompleted,
        boolean lessonCompleted
    ) {}

    public record WatchCompletionResult(
        UUID activityId,
        String status,
        int completedItems,
        int totalItems,
        boolean activityCompleted,
        boolean lessonCompleted
    ) {}
}
