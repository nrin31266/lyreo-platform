package com.lyreo.curriculum.application;

import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class CurriculumCompletionListener {
    private final CurriculumProgressRepository repository;

    public CurriculumCompletionListener(CurriculumProgressRepository repository) {
        this.repository = repository;
    }

    @ApplicationModuleListener
    public void onLessonCompleted(LessonCompletedEvent event) {
        completeReferences(event.learnerId(), ContentType.LESSON, event.lessonId());
    }

    @ApplicationModuleListener
    public void onToeicCompleted(ToeicAttemptCompletedEvent event) {
        ContentType type = "DRILL".equals(event.mode())
            ? ContentType.TOEIC_DRILL
            : ContentType.TOEIC_TEST;
        completeReferences(event.learnerId(), type, event.testId());
    }

    private void completeReferences(UUID learnerId, ContentType type, UUID contentId) {
        for (var itemId : repository.itemIdsReferencing(type, contentId)) {
            repository.complete(learnerId, itemId);
        }
    }
}
