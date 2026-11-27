package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.LearnerLevel;
import com.lyreo.gamification.domain.LevelProgression;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.gamification.domain.XpTransaction;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcXpLedger implements XpLedger {
    private final NamedParameterJdbcTemplate jdbc;
    private final DiamondLedger diamondLedger;

    public JdbcXpLedger(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, null);
    }

    public JdbcXpLedger(NamedParameterJdbcTemplate jdbc, DiamondLedger diamondLedger) {
        this.jdbc = jdbc;
        this.diamondLedger = diamondLedger;
    }

    @Override
    @Transactional
    public XpTransaction append(XpTransaction transaction, RewardPolicyConfig policy) {
        RewardPolicyConfig effective = policy != null ? policy : RewardPolicyConfig.defaults();
        int inserted = jdbc.update(
            """
            INSERT INTO xp_transaction(
                id, learner_id, source_type, amount, idempotency_key,
                rule_version, reference_id, created_at
            ) VALUES (
                :id, :learnerId, :sourceType, :amount, :idempotencyKey,
                :ruleVersion, :referenceId, :createdAt
            )
            ON CONFLICT(idempotency_key) DO NOTHING
            """,
            new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("learnerId", transaction.learnerId())
                .addValue("sourceType", transaction.sourceType())
                .addValue("amount", transaction.amount())
                .addValue("idempotencyKey", transaction.idempotencyKey())
                .addValue("ruleVersion", transaction.ruleVersion())
                .addValue("referenceId", transaction.referenceId())
                .addValue("createdAt", Timestamp.from(transaction.createdAt()))
        );

        if (inserted == 1) {
            int previousLevel = jdbc.query(
                "SELECT level FROM learner_level WHERE learner_id = :learnerId",
                Map.of("learnerId", transaction.learnerId()),
                (rs, rowNum) -> rs.getInt("level")
            ).stream().findFirst().orElse(1);

            jdbc.update(
                """
                INSERT INTO learner_level(learner_id, xp, level, updated_at)
                VALUES (
                    :learnerId,
                    GREATEST(0, :amount),
                    1,
                    now()
                )
                ON CONFLICT(learner_id) DO UPDATE SET
                    xp = GREATEST(0, learner_level.xp + :amount),
                    updated_at = now()
                """,
                Map.of(
                    "learnerId", transaction.learnerId(),
                    "amount", transaction.amount()
                )
            );
            Long currentXp = jdbc.queryForObject(
                "SELECT xp FROM learner_level WHERE learner_id = :learnerId",
                Map.of("learnerId", transaction.learnerId()),
                Long.class
            );
            int computedLevel = LevelProgression.evaluate(currentXp == null ? 0L : currentXp, effective).level();
            jdbc.update(
                "UPDATE learner_level SET level = :level WHERE learner_id = :learnerId",
                Map.of("learnerId", transaction.learnerId(), "level", computedLevel)
            );

            if (diamondLedger != null && computedLevel > Math.max(1, previousLevel)) {
                String ruleVersion = transaction.ruleVersion() != null && !transaction.ruleVersion().isBlank()
                    ? transaction.ruleVersion()
                    : effective.normalizedRuleVersion();
                Instant now = Instant.now();
                for (LevelProgression.MilestoneAward award :
                    LevelProgression.crossedMilestones(Math.max(1, previousLevel), computedLevel, effective)) {
                    diamondLedger.append(new DiamondTransaction(
                        UUID.randomUUID(),
                        transaction.learnerId(),
                        DiamondTransaction.Type.LEVEL_MILESTONE,
                        award.diamonds(),
                        "level-milestone:" + transaction.learnerId() + ":" + award.level(),
                        "LEVEL_MILESTONE",
                        null,
                        now,
                        "Thưởng cột mốc đạt cấp " + award.level(),
                        ruleVersion
                    ));
                }
            }
        }
        return transaction;
    }

    @Override
    public LearnerLevel findLevel(UUID learnerId) {
        return jdbc.query(
            """
            SELECT learner_id, xp, level, updated_at
            FROM learner_level
            WHERE learner_id = :learnerId
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new LearnerLevel(
                rs.getObject("learner_id", UUID.class),
                rs.getLong("xp"),
                rs.getInt("level"),
                rs.getTimestamp("updated_at").toInstant()
            )
        ).stream().findFirst().orElseGet(() -> LearnerLevel.empty(learnerId));
    }
}
