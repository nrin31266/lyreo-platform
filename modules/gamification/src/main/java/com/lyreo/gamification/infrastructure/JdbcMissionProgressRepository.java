package com.lyreo.gamification.infrastructure;

import com.lyreo.gamification.application.MissionProgressRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcMissionProgressRepository implements MissionProgressRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMissionProgressRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MissionTarget> activeByMetric(String metric) {
        return jdbc.query(
            """
            SELECT id, target, recurrence, diamond_reward
              FROM mission_definition
             WHERE enabled = true AND metric = :metric
            """,
            Map.of("metric", metric),
            (rs, rowNumber) -> new MissionTarget(
                rs.getObject("id", UUID.class),
                rs.getInt("target"),
                rs.getString("recurrence"),
                rs.getInt("diamond_reward")
            )
        );
    }

    @Override
    public void increment(UUID learnerId, UUID missionId, String periodKey, int target, int amount) {
        jdbc.update(
            """
            INSERT INTO mission_progress(learner_id, mission_id, period_key, progress, completed_at)
            VALUES(
              :learnerId,:missionId,:periodKey,LEAST(:target,:amount),
              CASE WHEN :amount >= :target THEN now() END
            )
            ON CONFLICT(learner_id,mission_id,period_key) DO UPDATE SET
              progress=LEAST(:target,mission_progress.progress+:amount),
              completed_at=CASE
                WHEN mission_progress.completed_at IS NULL
                 AND mission_progress.progress+:amount >= :target THEN now()
                ELSE mission_progress.completed_at
              END
            """,
            Map.of(
                "learnerId", learnerId,
                "missionId", missionId,
                "periodKey", periodKey,
                "target", target,
                "amount", amount
            )
        );
    }

    @Override
    public boolean markClaimedIfCompleted(UUID learnerId, UUID missionId, String periodKey) {
        var rows = jdbc.query(
            """
            UPDATE mission_progress
               SET claimed_at=now()
             WHERE learner_id=:learner
               AND mission_id=:mission
               AND period_key=:period
               AND completed_at IS NOT NULL
               AND claimed_at IS NULL
            RETURNING 1
            """,
            Map.of("learner", learnerId, "mission", missionId, "period", periodKey),
            (rs, rowNumber) -> rs.getInt(1)
        );
        return !rows.isEmpty();
    }
}
