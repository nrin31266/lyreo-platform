package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.LessonActivityWriter;
import com.lyreo.lesson.domain.LessonActivityType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcLessonActivityWriter implements LessonActivityWriter {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLessonActivityWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(UUID lessonId, Set<LessonActivityType> activities) {
        jdbc.update("DELETE FROM lesson_activity WHERE lesson_id = :lessonId", Map.of("lessonId", lessonId));
        int position = 0;
        for (LessonActivityType type : activities) {
            jdbc.update("""
                INSERT INTO lesson_activity(id, lesson_id, activity_type, position, enabled)
                VALUES (:id, :lessonId, :type, :position, TRUE)
                """, Map.of(
                    "id", UUID.randomUUID(),
                    "lessonId", lessonId,
                    "type", type.name(),
                    "position", position++
                ));
        }
    }
}
