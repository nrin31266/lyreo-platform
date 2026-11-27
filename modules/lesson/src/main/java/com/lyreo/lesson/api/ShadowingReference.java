package com.lyreo.lesson.api;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.LessonPracticeRepository;
import com.lyreo.lesson.domain.authoring.CaptionCueText;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

@Component
@NamedInterface(value = "shadowing", propagate = false)
public class ShadowingReference {
    private final AuthoringRepository lessons;
    private final LessonPracticeRepository practice;
    private final EntitlementService entitlements;

    public ShadowingReference(AuthoringRepository lessons) {
        this(lessons, null, (EntitlementService) null);
    }

    public ShadowingReference(AuthoringRepository lessons, LessonPracticeRepository practice) {
        this(lessons, practice, (EntitlementService) null);
    }

    public ShadowingReference(
        AuthoringRepository lessons,
        LessonPracticeRepository practice,
        EntitlementService entitlements
    ) {
        this.lessons = lessons;
        this.practice = practice;
        this.entitlements = entitlements;
    }

    @Autowired
    public ShadowingReference(
        AuthoringRepository lessons,
        LessonPracticeRepository practice,
        ObjectProvider<EntitlementService> entitlementsProvider
    ) {
        this(lessons, practice, entitlementsProvider == null ? null : entitlementsProvider.getIfAvailable());
    }

    public void requireLearnerAccess(UUID learnerId, UUID lessonId) {
        if (practice == null || entitlements == null || learnerId == null || lessonId == null) {
            return;
        }
        practice.findLessonAccess(lessonId).ifPresent(access -> {
            if ("FEATURE".equals(access.accessMode())
                && access.requiredFeatureKey() != null
                && !access.requiredFeatureKey().isBlank()) {
                entitlements.requireFeature(learnerId, FeatureKey.of(access.requiredFeatureKey()));
            }
        });
    }

    public Sentence read(UUID lessonId, UUID sentenceId, long revision, boolean lock) {
        var lesson = lessons.find(lessonId, lock)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson not found"));
        if (lesson.revision != revision) {
            throw new StateConflictException("Reload the lesson before starting a new pronunciation attempt");
        }
        var sentence = lesson.sentences.stream().filter(row -> sentenceId.equals(row.id)).findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Lesson sentence not found"));
        boolean playable = "YOUTUBE".equals(lesson.sourceType) ? lesson.videoId != null : lesson.audioObjectKey != null;
        if (!playable || sentence.hidden || !"LEARN".equals(sentence.usage) || !sentence.shadowingSelected
            || !lesson.options.activities().contains("SHADOWING")) {
            throw new RequestValidationException("Choose a saved Shadowing sentence with a playable reference");
        }
        Long start = sentence.coarseStartMs;
        Long end = sentence.coarseEndMs;
        if (!sentence.words.isEmpty()) {
            start = sentence.words.getFirst().startMs();
            end = sentence.words.getLast().endMs();
        }
        String text = CaptionCueText.speech(sentence.text);
        if (start == null || end == null || start < 0 || end <= start || end > lesson.endMs - lesson.startMs
            || text.isBlank() || text.length() > 6_000) {
            throw new RequestValidationException("The sentence needs text and a valid reference audio range");
        }
        return new Sentence(revision, text, start, end);
    }

    public LearnerSentence readForLearner(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision,
        boolean lock
    ) {
        if (practice == null || !practice.isLessonAvailable(lessonId)) {
            throw new ResourceNotFoundException("Lesson not found");
        }
        var target = practice.findShadowingTarget(lessonId, activityId, sentenceId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Shadowing activity/sentence does not belong to this lesson"));

        var authoringOpt = lessons.find(lessonId, lock);
        if (authoringOpt.isPresent()) {
            var lesson = authoringOpt.get();
            if (lesson.publishedAt == null) {
                throw new ResourceNotFoundException("Lesson not found");
            }
            if (lesson.revision != revision) {
                throw new StaleRevisionException(
                    "Reload the lesson before starting a new pronunciation attempt",
                    lesson.revision
                );
            }
            var sentence = lesson.sentences.stream()
                .filter(row -> sentenceId.equals(row.id))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Lesson sentence not found"));
            boolean playable = "YOUTUBE".equals(lesson.sourceType)
                ? lesson.videoId != null
                : lesson.audioObjectKey != null;
            if (!playable || sentence.hidden || !"LEARN".equals(sentence.usage)
                || !sentence.shadowingSelected
                || !lesson.options.activities().contains("SHADOWING")) {
                throw new RequestValidationException("Choose a valid Shadowing sentence with a playable reference");
            }
            Long start = sentence.coarseStartMs;
            Long end = sentence.coarseEndMs;
            if (!sentence.words.isEmpty()) {
                start = sentence.words.getFirst().startMs();
                end = sentence.words.getLast().endMs();
            }
            String text = CaptionCueText.speech(sentence.text);
            if (start == null || end == null || start < 0 || end <= start
                || end > lesson.endMs - lesson.startMs
                || text.isBlank() || text.length() > 6_000) {
                throw new RequestValidationException("The sentence needs text and a valid reference audio range");
            }
            return new LearnerSentence(lessonId, activityId, sentenceId, revision, text, start, end);
        }

        if (revision != 0L) {
            throw new StaleRevisionException(
                "Reload the lesson before starting a new pronunciation attempt",
                0L
            );
        }
        String text = CaptionCueText.speech(target.expectedText());
        Long start = target.audioStartMs();
        Long end = target.audioEndMs();
        if (!target.playable() || start == null || end == null || start < 0 || end <= start
            || text.isBlank() || text.length() > 6_000) {
            throw new RequestValidationException("The sentence needs text and a valid reference audio range");
        }
        return new LearnerSentence(lessonId, activityId, sentenceId, revision, text, start, end);
    }

    @NamedInterface(value = "shadowing", propagate = false)
    public record Sentence(long revision, String text, long startMs, long endMs) {}

    @NamedInterface(value = "shadowing", propagate = false)
    public record LearnerSentence(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision,
        String text,
        long startMs,
        long endMs
    ) {}
}
