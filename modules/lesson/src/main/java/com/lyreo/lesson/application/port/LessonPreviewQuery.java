package com.lyreo.lesson.application.port;

import com.lyreo.lesson.application.preview.LessonPreviewView;

import java.util.Optional;
import java.util.UUID;

/** Query-side projection used by Admin preview without leaking persistence entities. */
public interface LessonPreviewQuery {
    Optional<LessonPreviewView> find(UUID lessonId);
}
