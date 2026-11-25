package com.lyreo.curriculum.application;

import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
            event.lessonId()
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
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            type,
            event.testCatalogId()
        );
    }

    @ApplicationModuleListener
    public void onGrammarSessionCompleted(GrammarSessionCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("grammar-session:" + event.learnerId() + ":" + event.catalogId());
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.GRAMMAR_PRACTICE,
            event.catalogId()
        );
    }

    @ApplicationModuleListener
    public void onVocabularyUnitCompleted(VocabularyUnitCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("vocab-unit:" + event.learnerId() + ":" + event.unitId());
        curriculumService.onContentCompleted(
            factId,
            event.learnerId(),
            ContentType.VOCABULARY_REVIEW,
            event.unitId()
        );
    }

    private static UUID deterministicFactId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
