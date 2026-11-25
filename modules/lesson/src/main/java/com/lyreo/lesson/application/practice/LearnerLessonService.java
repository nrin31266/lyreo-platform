package com.lyreo.lesson.application.practice;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import com.lyreo.lesson.application.port.LearnerLessonQuery;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Application service for learner lesson catalog, detail, hint-masked activities, audio, and attempts. */
public class LearnerLessonService {
    private final LearnerLessonQuery query;
    private final DictationMaskingPolicy maskingPolicy;
    private final ObjectStoragePort storage;

    public LearnerLessonService(
        LearnerLessonQuery query,
        DictationMaskingPolicy maskingPolicy,
        ObjectStoragePort storage
    ) {
        this.query = query;
        this.maskingPolicy = maskingPolicy;
        this.storage = storage;
    }

    public LearnerLessonQuery.CatalogPage listCatalog(
        UUID learnerId,
        UUID categoryId,
        String cefr,
        String sourceType,
        String activity,
        String q,
        int page,
        int size
    ) {
        validatePage(page, size);
        if (q != null && q.length() > 200) {
            throw new RequestValidationException("Search query must be at most 200 characters");
        }
        return query.listPublishedCatalog(
            learnerId,
            categoryId,
            normalizeFilter(cefr),
            normalizeFilter(sourceType),
            normalizeFilter(activity),
            q == null ? "" : q.trim(),
            page,
            size
        );
    }

    public LearnerLessonQuery.LessonDetail getLessonDetail(UUID learnerId, UUID lessonId) {
        return query.findPublishedDetail(learnerId, lessonId)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson not found"));
    }

    public ActivityView getActivity(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String hintLevelRaw,
        Boolean properNounHintsOverride
    ) {
        DictationMaskingPolicy.HintLevel hintLevel = DictationMaskingPolicy.HintLevel.parse(hintLevelRaw);
        var context = query.findActivityContext(learnerId, lessonId, activityId)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson activity not found"));

        boolean isDictation = "DICTATION".equals(context.activity().activityType());
        boolean properNounHintsEnabled = properNounHintsOverride != null
            ? properNounHintsOverride
            : query.readProperNounHintsPreference(learnerId);

        List<SentenceView> sentences = context.sentences().stream()
            .map(sentence -> toSentenceView(sentence, isDictation, hintLevel, properNounHintsEnabled))
            .toList();

        return new ActivityView(
            context.lessonId(),
            context.revision(),
            context.videoId(),
            context.sourceStartMs(),
            context.sourceEndMs(),
            context.audioReady(),
            context.activity(),
            isDictation ? hintLevel.name() : null,
            isDictation ? properNounHintsEnabled : null,
            sentences
        );
    }

    public LessonAudio readAudio(UUID learnerId, UUID lessonId) {
        var asset = query.findAudioForLearner(learnerId, lessonId)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson audio not found"));
        if (!storage.exists(asset.objectKey())) {
            throw new ResourceNotFoundException("Lesson audio is unavailable");
        }
        try (var input = storage.get(asset.objectKey())) {
            return new LessonAudio(input.readAllBytes(), asset.contentType());
        } catch (IOException failure) {
            throw new ServiceUnavailableException("LESSON_AUDIO_UNAVAILABLE", "Lesson audio is unavailable", failure);
        }
    }

    public LearnerLessonQuery.AttemptPage listAttempts(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String activityType,
        UUID sentenceId,
        int page,
        int size
    ) {
        validatePage(page, size);
        return query.listAttempts(
            learnerId,
            lessonId,
            activityId,
            normalizeFilter(activityType),
            sentenceId,
            page,
            size
        );
    }

    private SentenceView toSentenceView(
        LearnerLessonQuery.RawActivitySentence sentence,
        boolean isDictation,
        DictationMaskingPolicy.HintLevel hintLevel,
        boolean properNounHintsEnabled
    ) {
        boolean attempted = sentence.latestAttempt() != null;
        if (!isDictation) {
            return new SentenceView(
                sentence.id(),
                sentence.position(),
                sentence.audioStartMs(),
                sentence.audioEndMs(),
                sentence.text(),
                sentence.text(),
                false,
                sentence.words(),
                List.of(),
                sentence.translation(),
                sentence.ipa(),
                sentence.ipaAccent(),
                sentence.ipaVersion(),
                sentence.vocabulary(),
                sentence.grammar(),
                sentence.notes(),
                sentence.thoughtGroups(),
                sentence.latestAttempt() == null ? null : sentence.latestAttempt().serverScore(),
                sentence.bestScore(),
                sentence.latestAttempt()
            );
        }

        var masked = maskingPolicy.apply(
            sentence.text(),
            sentence.cloze(),
            sentence.words(),
            sentence.ipaReviewIssues(),
            sentence.hints(),
            sentence.entitySpans(),
            hintLevel,
            properNounHintsEnabled,
            attempted,
            sentence.translation(),
            sentence.ipa(),
            sentence.ipaAccent(),
            sentence.ipaVersion(),
            sentence.vocabulary(),
            sentence.grammar(),
            sentence.notes(),
            sentence.thoughtGroups()
        );

        return new SentenceView(
            sentence.id(),
            sentence.position(),
            sentence.audioStartMs(),
            sentence.audioEndMs(),
            masked.text(),
            masked.expectedText(),
            attempted,
            masked.words(),
            masked.properNounHints(),
            masked.translation(),
            masked.ipa(),
            masked.ipaAccent(),
            masked.ipaVersion(),
            masked.vocabulary(),
            masked.grammar(),
            masked.notes(),
            masked.thoughtGroups(),
            sentence.latestAttempt() == null ? null : sentence.latestAttempt().serverScore(),
            sentence.bestScore(),
            sentence.latestAttempt()
        );
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new RequestValidationException("page must be >= 0 and size must be between 1 and 100");
        }
    }

    private static String normalizeFilter(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public record ActivityView(
        UUID lessonId,
        long revision,
        String videoId,
        Long sourceStartMs,
        Long sourceEndMs,
        boolean audioReady,
        LearnerLessonQuery.ActivityProgressItem activity,
        String hintLevel,
        Boolean properNounHints,
        List<SentenceView> sentences
    ) {}

    public record SentenceView(
        UUID id,
        int position,
        Long audioStartMs,
        Long audioEndMs,
        String text,
        String expectedText,
        boolean attempted,
        List<DictationMaskingPolicy.WordView> words,
        List<DictationMaskingPolicy.ProperNounHint> properNounHints,
        String translation,
        String ipa,
        String ipaAccent,
        String ipaVersion,
        List<AuthoringSentence.Annotation> vocabulary,
        List<AuthoringSentence.Annotation> grammar,
        List<AuthoringSentence.Note> notes,
        List<AuthoringSentence.Group> thoughtGroups,
        Integer latestScore,
        Integer bestScore,
        LearnerLessonQuery.LatestSentenceAttempt latestAttempt
    ) {}

    public record LessonAudio(byte[] bytes, String contentType) {}
}
