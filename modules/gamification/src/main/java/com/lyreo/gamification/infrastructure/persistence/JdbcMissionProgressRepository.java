package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.MissionProgressRepository;
import com.lyreo.gamification.domain.AdminMissionDefinition;
import com.lyreo.gamification.domain.MissionDefinition;
import com.lyreo.gamification.domain.MissionView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
    public List<MissionRewardTarget> activeRewardTargetsByMetric(String metric) {
        return jdbc.query(
            """
            SELECT id, code, title, target, recurrence, diamond_reward, xp_reward, rule_version
              FROM mission_definition
             WHERE enabled = true AND metric = :metric
            """,
            Map.of("metric", metric),
            (rs, rowNumber) -> new MissionRewardTarget(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getInt("target"),
                rs.getString("recurrence"),
                rs.getInt("diamond_reward"),
                rs.getInt("xp_reward"),
                rs.getString("rule_version")
            )
        );
    }

    @Override
    public Optional<MissionRewardTarget> findActiveRewardTargetById(UUID missionId) {
        return jdbc.query(
            """
            SELECT id, code, title, target, recurrence, diamond_reward, xp_reward, rule_version
              FROM mission_definition
             WHERE enabled = true AND id = :id
            """,
            Map.of("id", missionId),
            (rs, rowNumber) -> new MissionRewardTarget(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getInt("target"),
                rs.getString("recurrence"),
                rs.getInt("diamond_reward"),
                rs.getInt("xp_reward"),
                rs.getString("rule_version")
            )
        ).stream().findFirst();
    }

    @Override
    public Optional<MissionProgressStatus> findMissionProgressStatus(
        UUID learnerId,
        UUID missionId,
        String periodKey
    ) {
        return jdbc.query(
            """
            SELECT progress, completed_at, claimed_at, reward_snapshot_json::text AS snapshot_json
              FROM mission_progress
             WHERE learner_id = :learnerId
               AND mission_id = :missionId
               AND period_key = :periodKey
            """,
            Map.of(
                "learnerId", learnerId,
                "missionId", missionId,
                "periodKey", periodKey
            ),
            (rs, rowNumber) -> {
                Timestamp completed = rs.getTimestamp("completed_at");
                Timestamp claimed = rs.getTimestamp("claimed_at");
                return new MissionProgressStatus(
                    rs.getInt("progress"),
                    completed == null ? null : completed.toInstant(),
                    claimed == null ? null : claimed.toInstant(),
                    rs.getString("snapshot_json")
                );
            }
        ).stream().findFirst();
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
        return markClaimedIfCompleted(learnerId, missionId, periodKey, null);
    }

    @Override
    public boolean markClaimedIfCompleted(
        UUID learnerId,
        UUID missionId,
        String periodKey,
        String rewardSnapshotJson
    ) {
        var rows = jdbc.query(
            """
            UPDATE mission_progress
               SET claimed_at = now(),
                   reward_snapshot_json = COALESCE(CAST(:snapshot AS jsonb), reward_snapshot_json)
             WHERE learner_id = :learner
               AND mission_id = :mission
               AND period_key = :period
               AND completed_at IS NOT NULL
               AND claimed_at IS NULL
            RETURNING 1
            """,
            new MapSqlParameterSource()
                .addValue("learner", learnerId)
                .addValue("mission", missionId)
                .addValue("period", periodKey)
                .addValue("snapshot", rewardSnapshotJson),
            (rs, rowNumber) -> rs.getInt(1)
        );
        return !rows.isEmpty();
    }

    @Override
    public List<MissionView> listActiveMissionsForLearner(
        UUID learnerId,
        String dailyPeriodKey,
        String weeklyPeriodKey,
        Instant dailyEndsAt,
        Instant weeklyEndsAt
    ) {
        return jdbc.query(
            """
            SELECT
                md.id,
                md.code,
                md.title,
                md.metric,
                md.recurrence,
                md.target,
                md.xp_reward,
                md.diamond_reward,
                md.rule_version,
                md.category,
                md.difficulty,
                CASE
                    WHEN md.recurrence = 'WEEKLY' THEN :weeklyPeriodKey
                    WHEN md.recurrence = 'ONE_TIME' THEN 'ONE_TIME'
                    ELSE :dailyPeriodKey
                END AS resolved_period_key,
                COALESCE(mp.progress, 0) AS progress,
                mp.completed_at,
                mp.claimed_at
            FROM mission_definition md
            LEFT JOIN mission_progress mp
                ON mp.mission_id = md.id
               AND mp.learner_id = :learnerId
               AND mp.period_key = CASE
                   WHEN md.recurrence = 'WEEKLY' THEN :weeklyPeriodKey
                   WHEN md.recurrence = 'ONE_TIME' THEN 'ONE_TIME'
                   ELSE :dailyPeriodKey
               END
            WHERE md.enabled = true
            ORDER BY md.code ASC
            """,
            Map.of(
                "learnerId", learnerId,
                "dailyPeriodKey", dailyPeriodKey,
                "weeklyPeriodKey", weeklyPeriodKey
            ),
            (rs, rowNum) -> {
                String recurrence = rs.getString("recurrence");
                Timestamp completedTs = rs.getTimestamp("completed_at");
                Timestamp claimedTs = rs.getTimestamp("claimed_at");
                Instant periodEndsAt = switch (recurrence) {
                    case "DAILY" -> dailyEndsAt;
                    case "WEEKLY" -> weeklyEndsAt;
                    default -> null;
                };
                return new MissionView(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getString("metric"),
                    recurrence,
                    rs.getInt("target"),
                    rs.getInt("progress"),
                    completedTs != null,
                    completedTs == null ? null : completedTs.toInstant(),
                    claimedTs != null,
                    claimedTs == null ? null : claimedTs.toInstant(),
                    rs.getInt("xp_reward"),
                    rs.getInt("diamond_reward"),
                    rs.getString("resolved_period_key"),
                    periodEndsAt,
                    rs.getString("rule_version"),
                    rs.getString("category"),
                    rs.getString("difficulty")
                );
            }
        );
    }

    @Override
    public List<AdminMissionDefinition> listAllMissions() {
        return jdbc.query(
            """
            SELECT id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled,
                   category, difficulty
            FROM mission_definition
            ORDER BY code ASC
            """,
            (rs, rowNum) -> mapAdminMission(rs)
        );
    }

    @Override
    public Optional<AdminMissionDefinition> findMissionById(UUID id) {
        return jdbc.query(
            """
            SELECT id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled,
                   category, difficulty
            FROM mission_definition
            WHERE id = :id
            """,
            Map.of("id", id),
            (rs, rowNum) -> mapAdminMission(rs)
        ).stream().findFirst();
    }

    @Override
    public AdminMissionDefinition createMission(AdminMissionDefinition mission) {
        jdbc.update(
            """
            INSERT INTO mission_definition(
                id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled,
                category, difficulty
            ) VALUES (
                :id, :code, :title, :metric, :target, :xpReward, :diamondReward, :recurrence, :ruleVersion, :enabled,
                :category, :difficulty
            )
            """,
            missionParams(mission)
        );
        return mission;
    }

    @Override
    public Optional<AdminMissionDefinition> updateMission(AdminMissionDefinition mission) {
        return jdbc.query(
            """
            UPDATE mission_definition
            SET code = :code,
                title = :title,
                metric = :metric,
                target = :target,
                xp_reward = :xpReward,
                diamond_reward = :diamondReward,
                recurrence = :recurrence,
                rule_version = :ruleVersion,
                enabled = :enabled,
                category = :category,
                difficulty = :difficulty
            WHERE id = :id
            RETURNING id, code, title, metric, target, xp_reward, diamond_reward, recurrence, rule_version, enabled,
                      category, difficulty
            """,
            missionParams(mission),
            (rs, rowNum) -> mapAdminMission(rs)
        ).stream().findFirst();
    }

    private static AdminMissionDefinition mapAdminMission(ResultSet rs) throws SQLException {
        return new AdminMissionDefinition(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("title"),
            MissionDefinition.Metric.valueOf(rs.getString("metric")),
            rs.getInt("target"),
            rs.getInt("xp_reward"),
            rs.getInt("diamond_reward"),
            MissionDefinition.Recurrence.valueOf(rs.getString("recurrence")),
            rs.getString("rule_version"),
            rs.getBoolean("enabled"),
            rs.getString("category"),
            rs.getString("difficulty")
        );
    }

    private static MapSqlParameterSource missionParams(AdminMissionDefinition mission) {
        return new MapSqlParameterSource()
            .addValue("id", mission.id())
            .addValue("code", mission.code())
            .addValue("title", mission.title())
            .addValue("metric", mission.metric().name())
            .addValue("target", mission.target())
            .addValue("xpReward", mission.xpReward())
            .addValue("diamondReward", mission.diamondReward())
            .addValue("recurrence", mission.recurrence().name())
            .addValue("ruleVersion", mission.ruleVersion())
            .addValue("enabled", mission.enabled())
            .addValue("category", mission.category() == null ? "GENERAL" : mission.category())
            .addValue("difficulty", mission.difficulty() == null ? "NORMAL" : mission.difficulty());
    }
}
