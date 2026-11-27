package com.lyreo.lesson.application.practice;

import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.lesson.api.LearnerShadowingCompletionPort;
import com.lyreo.lesson.application.port.LessonPracticeRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/** Records scored learner Shadowing attempts and advances Lesson progress/completion. */
public class LearnerShadowingCompletionService implements LearnerShadowingCompletionPort {
    private final LessonPracticeRepository repository;
    private final ApplicationEventPublisher events;

    public LearnerShadowingCompletionService(
        LessonPracticeRepository repository,
        ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.events = events;
    }

    @Override
    @Transactional
    public ShadowingCompletionResult recordScoredAttempt(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        UUID attemptId,
        int serverScore,
        String transcript,
        String detailJson
    ) {
        int clampedScore = Math.clamp(serverScore, 0, 100);
        repository.appendShadowingAttempt(
            attemptId,
            learnerId,
            lessonId,
            activityId,
            sentenceId,
            clampedScore,
            transcript,
            detailJson
        );

        var progress = repository.refreshActivityProgress(
            learnerId,
            lessonId,
            activityId,
            LessonPracticeService.DEFAULT_COMPLETION_SCORE
        );

        boolean activityCompleted = repository.tryMarkActivityCompleted(learnerId, activityId);
        Instant occurredAt = Instant.now();
        if (activityCompleted) {
            List<UUID> completedSentenceIds = repository.findCompletedSentenceIds(
                learnerId,
                activityId,
                LessonPracticeService.DEFAULT_COMPLETION_SCORE
            );
            events.publishEvent(new LessonActivityCompletedEvent(
                LessonPracticeService.activityFactId(learnerId, activityId),
                learnerId,
                lessonId,
                activityId,
                "SHADOWING",
                progress.bestScore() == null ? clampedScore : progress.bestScore(),
                occurredAt,
                completedSentenceIds
            ));
        }

        boolean lessonCompleted = repository.tryMarkLessonCompleted(learnerId, lessonId);
        if (lessonCompleted) {
            events.publishEvent(new LessonCompletedEvent(
                LessonPracticeService.lessonFactId(learnerId, lessonId),
                learnerId,
                lessonId,
                occurredAt
            ));
        }

        return new ShadowingCompletionResult(
            attemptId,
            clampedScore,
            progress.completedItems(),
            progress.totalItems(),
            activityCompleted,
            lessonCompleted
        );
    }
}
