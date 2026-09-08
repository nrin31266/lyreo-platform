package com.lyreo.curriculum.infrastructure;

import com.lyreo.curriculum.application.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcCurriculumProgressRepository implements CurriculumProgressRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCurriculumProgressRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> itemIdsReferencing(ContentType type, UUID referenceId) {
        return jdbc.query(
            """
            SELECT id
            FROM curriculum_item
            WHERE content_type = :type
              AND content_reference_id = :referenceId
            """,
            Map.of("type", type.name(), "referenceId", referenceId),
            (rs, rowNumber) -> rs.getObject("id", UUID.class)
        );
    }

    @Override
    public void complete(UUID learnerId, UUID itemId) {
        jdbc.update(
            """
            INSERT INTO curriculum_item_progress(
                learner_id, item_id, status, started_at, completed_at
            ) VALUES (
                :learnerId, :itemId, 'COMPLETED', now(), now()
            )
            ON CONFLICT(learner_id, item_id) DO UPDATE SET
                status = 'COMPLETED',
                completed_at = COALESCE(curriculum_item_progress.completed_at, now())
            """,
            Map.of("learnerId", learnerId, "itemId", itemId)
        );
    }
}
