package com.lyreo.lesson.application;

import com.lyreo.lesson.domain.Lesson;
import java.util.Optional;

/**
 * Converts an external lesson source into an object-storage audio artifact when the downstream
 * AI runtime requires direct audio bytes/URL.
 *
 * <p>This port exists mainly for YouTube in the current product assumption. It intentionally
 * lives outside FastAPI so the Python service remains an AI capability runtime rather than a
 * media-ingestion/business workflow service.</p>
 */
public interface LessonSourceMaterializer {

    /**
     * @return an object key containing processable audio, or empty when no materialization is
     * needed for this source.
     */
    Optional<String> materialize(Lesson lesson);
}
