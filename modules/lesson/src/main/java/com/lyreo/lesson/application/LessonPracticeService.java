package com.lyreo.lesson.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner Lesson practice application service.
 *
 * <p>The client submits evidence (for Dictation: answer text), never a trusted score or reward.
 * The server computes score, appends an attempt, projects progress and publishes completion events
 * in one short transaction.</p>
 */
public class LessonPracticeService {
    static final int DEFAULT_COMPLETION_SCORE = 70;

    private final LessonPracticeRepository repository;
    private final DictationScoringPolicy dictationScoring;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    public LessonPracticeService(
        LessonPracticeRepository repository,
        DictationScoringPolicy dictationScoring,
        ApplicationEventPublisher events,
        ObjectMapper mapper
    ) {
        this.repository = repository;
        this.dictationScoring = dictationScoring;
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public DictationAttemptResult submitDictation(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String answer
    ) {
        if (answer == null) throw new RequestValidationException("answer is required");

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
        if (activityCompleted) {
            events.publishEvent(new LessonActivityCompletedEvent(
                learnerId,
                lessonId,
                activityId,
                "DICTATION",
                progress.bestScore() == null ? score.percent() : progress.bestScore(),
                occurredAt
            ));
        }

        boolean lessonCompleted = repository.tryMarkLessonCompleted(learnerId, lessonId);
        if (lessonCompleted) {
            events.publishEvent(new LessonCompletedEvent(learnerId, lessonId, occurredAt));
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
}
