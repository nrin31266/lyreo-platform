package com.lyreo.lesson.application;

import com.lyreo.lesson.domain.Lesson;
import com.lyreo.lesson.domain.LessonSentence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LessonRepository {
    Lesson createDraft(String title, String sourceType, String sourceText, String sourceReference);
    Optional<Lesson> findById(UUID lessonId);
    List<LessonSentence> sentences(UUID lessonId);
    void replaceSentences(UUID lessonId, List<String> sentences);
    void setCanonicalAudio(UUID lessonId, String objectKey);
    void markProcessing(UUID lessonId);
    void markReady(UUID lessonId);
    void markFailed(UUID lessonId);
    void markCancelled(UUID lessonId);
}
