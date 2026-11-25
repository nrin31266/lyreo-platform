package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.AchievementRepository;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AchievementView;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAchievementRepository implements AchievementRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAchievementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordDistinctItem(UUID learnerId, String metric, UUID itemId) {
        int inserted = jdbc.update(
            """
            INSERT INTO learner_achievement_item(learner_id, metric, item_id)
            VALUES (:learnerId, :metric, :itemId)
            ON CONFLICT (learner_id, metric, item_id) DO NOTHING
            """,
            Map.of("learnerId", learnerId, "metric", metric, "itemId", itemId)
        );
        return inserted == 1;
    }

    @Override
    public int countDistinctItems(UUID learnerId, String metric) {
        Integer count = jdbc.queryForObject(
            """
            SELECT count(*)
            FROM learner_achievement_item
            WHERE learner_id = :learnerId AND metric = :metric
            """,
            Map.of("learnerId", learnerId, "metric", metric),
            Integer.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public List<AchievementDefinition> findEnabledByMetric(String metric) {
        return jdbc.query(
            """
            SELECT id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
            FROM achievement_definition
            WHERE enabled = true AND metric = :metric
            ORDER BY target ASC, code ASC
            """,
            Map.of("metric", metric),
            (rs, rowNum) -> new AchievementDefinition(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("metric"),
                rs.getInt("target"),
                rs.getInt("xp_reward"),
                rs.getInt("diamond_reward"),
                rs.getString("rule_version"),
                rs.getBoolean("enabled")
            )
        );
    }

    @Override
    public void upsertProgress(UUID learnerId, UUID achievementId, int progress) {
        jdbc.update(
            """
            INSERT INTO learner_achievement(learner_id, achievement_id, progress)
            VALUES (:learnerId, :achievementId, :progress)
            ON CONFLICT (learner_id, achievement_id) DO UPDATE SET
                progress = GREATEST(learner_achievement.progress, excluded.progress)
            """,
            Map.of(
                "learnerId", learnerId,
                "achievementId", achievementId,
                "progress", Math.max(0, progress)
            )
        );
    }

    @Override
    public boolean markUnlockedIfEligible(
        UUID learnerId,
        UUID achievementId,
        int target,
        String rewardSnapshotJson
    ) {
        var rows = jdbc.query(
            """
            UPDATE learner_achievement
            SET unlocked_at = now(),
                reward_snapshot_json = CAST(:snapshot AS jsonb)
            WHERE learner_id = :learnerId
              AND achievement_id = :achievementId
              AND progress >= :target
              AND unlocked_at IS NULL
            RETURNING 1
            """,
            new MapSqlParameterSource()
                .addValue("learnerId", learnerId)
                .addValue("achievementId", achievementId)
                .addValue("target", target)
                .addValue("snapshot", rewardSnapshotJson),
            (rs, rowNum) -> rs.getInt(1)
        );
        return !rows.isEmpty();
    }

    @Override
    public List<AchievementView> listLearnerAchievements(UUID learnerId) {
        return jdbc.query(
            """
            SELECT
                ad.id,
                ad.code,
                ad.title,
                ad.description,
                ad.metric,
                ad.target,
                ad.xp_reward,
                ad.diamond_reward,
                ad.rule_version,
                COALESCE(la.progress, 0) AS progress,
                la.unlocked_at
            FROM achievement_definition ad
            LEFT JOIN learner_achievement la
                ON la.achievement_id = ad.id AND la.learner_id = :learnerId
            WHERE ad.enabled = true
            ORDER BY ad.code ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> {
                Timestamp unlockedTs = rs.getTimestamp("unlocked_at");
                return new AchievementView(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getString("description"),
                    rs.getString("metric"),
                    rs.getInt("target"),
                    rs.getInt("progress"),
                    unlockedTs != null,
                    unlockedTs == null ? null : unlockedTs.toInstant(),
                    rs.getInt("xp_reward"),
                    rs.getInt("diamond_reward"),
                    rs.getString("rule_version")
                );
            }
        );
    }

    @Override
    public List<AchievementDefinition> listAllAchievements() {
        return jdbc.query(
            """
            SELECT id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
            FROM achievement_definition
            ORDER BY code ASC
            """,
            (rs, rowNum) -> mapDefinition(rs)
        );
    }

    @Override
    public Optional<AchievementDefinition> findAchievementById(UUID id) {
        return jdbc.query(
            """
            SELECT id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
            FROM achievement_definition
            WHERE id = :id
            """,
            Map.of("id", id),
            (rs, rowNum) -> mapDefinition(rs)
        ).stream().findFirst();
    }

    @Override
    public AchievementDefinition createAchievement(AchievementDefinition achievement) {
        jdbc.update(
            """
            INSERT INTO achievement_definition(
                id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
            ) VALUES (
                :id, :code, :title, :description, :metric, :target, :xpReward, :diamondReward, :ruleVersion, :enabled
            )
            """,
            achievementParams(achievement)
        );
        return achievement;
    }

    @Override
    public Optional<AchievementDefinition> updateAchievement(AchievementDefinition achievement) {
        return jdbc.query(
            """
            UPDATE achievement_definition
            SET code = :code,
                title = :title,
                description = :description,
                metric = :metric,
                target = :target,
                xp_reward = :xpReward,
                diamond_reward = :diamondReward,
                rule_version = :ruleVersion,
                enabled = :enabled
            WHERE id = :id
            RETURNING id, code, title, description, metric, target, xp_reward, diamond_reward, rule_version, enabled
            """,
            achievementParams(achievement),
            (rs, rowNum) -> mapDefinition(rs)
        ).stream().findFirst();
    }

    private static AchievementDefinition mapDefinition(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AchievementDefinition(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("metric"),
            rs.getInt("target"),
            rs.getInt("xp_reward"),
            rs.getInt("diamond_reward"),
            rs.getString("rule_version"),
            rs.getBoolean("enabled")
        );
    }

    private static MapSqlParameterSource achievementParams(AchievementDefinition achievement) {
        return new MapSqlParameterSource()
            .addValue("id", achievement.id())
            .addValue("code", achievement.code())
            .addValue("title", achievement.title())
            .addValue("description", achievement.description())
            .addValue("metric", achievement.metric())
            .addValue("target", achievement.target())
            .addValue("xpReward", achievement.xpReward())
            .addValue("diamondReward", achievement.diamondReward())
            .addValue("ruleVersion", achievement.ruleVersion())
            .addValue("enabled", achievement.enabled());
    }
}
