package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.authoring.AuthoringCloze;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only query port for learner lesson catalog, detail, activities, audio, and attempts. */
public interface LearnerLessonQuery {

    CatalogPage listPublishedCatalog(
        UUID learnerId,
        UUID categoryId,
        String cefr,
        String sourceType,
        String activity,
        String query,
        int page,
        int size
    );

    Optional<LessonDetail> findPublishedDetail(UUID learnerId, UUID lessonId);

    Optional<ActivityContext> findActivityContext(UUID learnerId, UUID lessonId, UUID activityId);

    boolean readProperNounHintsPreference(UUID learnerId);

    Optional<AudioAsset> findAudioForLearner(UUID learnerId, UUID lessonId);

    AttemptPage listAttempts(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String activityType,
        UUID sentenceId,
        int page,
        int size
    );

    record CategorySummary(UUID id, String name, Map<String, String> translations) {}

    record ProgressSummary(
        String status,
        Instant startedAt,
        Instant completedAt,
        Instant lastActivityAt,
        int completedActivities,
        int totalPracticeActivities
    ) {}

    record CatalogItem(
        UUID id,
        String title,
        String description,
        String imageUrl,
        String sourceType,
        String cefr,
        String contentType,
        long revision,
        Long durationMs,
        boolean audioReady,
        String channelId,
        String channelTitle,
        List<CategorySummary> categories,
        List<String> activities,
        int sentenceCount,
        ProgressSummary progress,
        Instant updatedAt
    ) {}

    record CatalogPage(List<CatalogItem> items, long total, int page, int size) {}

    record ActivityProgressItem(
        UUID id,
        String activityType,
        int position,
        boolean enabled,
        String status,
        int completedItems,
        int totalItems,
        Integer bestScore,
        Instant startedAt,
        Instant completedAt,
        Instant lastPracticedAt
    ) {}

    record SentenceSummary(
        UUID id,
        int position,
        Long audioStartMs,
        Long audioEndMs,
        int wordCount,
        boolean dictationSelected,
        boolean shadowingSelected
    ) {}

    record LessonDetail(
        UUID id,
        String title,
        String description,
        String imageUrl,
        String sourceType,
        long revision,
        String videoId,
        Long sourceStartMs,
        Long sourceEndMs,
        boolean audioReady,
        String cefr,
        String contentType,
        String accent,
        String locale,
        String channelId,
        String channelTitle,
        List<CategorySummary> categories,
        ProgressSummary progress,
        List<ActivityProgressItem> activities,
        List<SentenceSummary> sentences,
        Instant updatedAt
    ) {}

    record LatestSentenceAttempt(
        UUID attemptId,
        int serverScore,
        String answerText,
        Instant createdAt
    ) {}

    record RawActivitySentence(
        UUID id,
        int position,
        String text,
        Long audioStartMs,
        Long audioEndMs,
        AuthoringCloze.Targets cloze,
        List<DictationMaskingPolicy.WordView> words,
        List<AuthoringSentence.IpaReviewIssue> ipaReviewIssues,
        List<AuthoringSentence.Hint> hints,
        List<DictationMaskingPolicy.EntitySpan> entitySpans,
        String translation,
        String ipa,
        String ipaAccent,
        String ipaVersion,
        List<AuthoringSentence.Annotation> vocabulary,
        List<AuthoringSentence.Annotation> grammar,
        List<AuthoringSentence.Note> notes,
        List<AuthoringSentence.Group> thoughtGroups,
        LatestSentenceAttempt latestAttempt,
        Integer bestScore
    ) {}

    record ActivityContext(
        UUID lessonId,
        long revision,
        String videoId,
        Long sourceStartMs,
        Long sourceEndMs,
        boolean audioReady,
        ActivityProgressItem activity,
        List<RawActivitySentence> sentences
    ) {}

    record AudioAsset(String objectKey, String contentType) {}

    record AttemptItem(
        UUID attemptId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String activityType,
        int serverScore,
        String answerText,
        String expectedText,
        Object detail,
        Instant createdAt
    ) {}

    record AttemptPage(List<AttemptItem> items, long total, int page, int size) {}
}
