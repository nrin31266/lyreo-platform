package com.lyreo.curriculum.application;

import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.curriculum.application.CurriculumService.CompletionEvidence;
import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class CurriculumCompletionListener {
    private final CurriculumService curriculumService;

    @Autowired
    public CurriculumCompletionListener(CurriculumService curriculumService) {
        this.curriculumService = curriculumService;
    }

    public CurriculumCompletionListener(CurriculumProgressRepository repository) {
        this(new CurriculumService(repository));
    }

    @ApplicationModuleListener
    public void onLessonCompleted(LessonCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : deterministicFactId("lesson:" + event.learnerId() + ":" + event.lessonId());
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.LESSON,
            event.lessonId(),
            CompletionEvidence.empty()
        );
    }

    @ApplicationModuleListener
    public void onLessonActivityCompleted(LessonActivityCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : deterministicFactId("lesson-activity:" + event.learnerId() + ":" + event.activityId());
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.LESSON,
            event.lessonId(),
            new CompletionEvidence(
                event.activityType(),
                Set.of(),
                Set.copyOf(event.completedSentenceIds()),
                Set.of(),
                Set.of(),
                null,
                Set.of()
            )
        );
    }

    @ApplicationModuleListener
    public void onToeicCompleted(ToeicAttemptCompletedEvent event) {
        ContentType type = "DRILL".equals(event.mode())
            ? ContentType.TOEIC_DRILL
            : ContentType.TOEIC_TEST;
        UUID factId = event.attemptId() != null
            ? event.attemptId()
            : deterministicFactId("toeic:" + event.learnerId() + ":" + event.testCatalogId());
        Set<Integer> completedParts = event.parts().stream()
            .filter(part -> part.total() > 0)
            .map(ToeicAttemptCompletedEvent.PartResult::part)
            .collect(Collectors.toSet());
        int totalQuestions = event.listeningTotal() + event.readingTotal();
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            type,
            event.testCatalogId(),
            new CompletionEvidence(
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                completedParts,
                totalQuestions > 0 ? totalQuestions : null,
                Set.of()
            )
        );
    }

    @ApplicationModuleListener
    public void onGrammarSessionCompleted(GrammarSessionCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("grammar-session:" + event.learnerId() + ":" + event.catalogId());
        UUID primaryRefId = event.topicId() != null ? event.topicId() : event.catalogId();
        Set<UUID> altRefs = (event.catalogId() != null && !event.catalogId().equals(primaryRefId))
            ? Set.of(event.catalogId())
            : Set.of();
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.GRAMMAR_PRACTICE,
            primaryRefId,
            new CompletionEvidence(
                null,
                Set.of(),
                Set.of(),
                Set.copyOf(event.subtopicIds()),
                Set.of(),
                event.totalCount() > 0 ? event.totalCount() : null,
                altRefs
            )
        );
    }

    @ApplicationModuleListener
    public void onVocabularyUnitCompleted(VocabularyUnitCompletedEvent event) {
        if (event.unitId() == null) {
            return;
        }
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("vocab-unit:" + event.learnerId() + ":" + event.unitId());
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.VOCABULARY_REVIEW,
            event.unitId(),
            new CompletionEvidence(
                null,
                Set.copyOf(event.completedEntryIds()),
                Set.of(),
                Set.of(),
                Set.of(),
                null,
                Set.of()
            )
        );
    }

    private static UUID deterministicFactId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
