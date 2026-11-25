package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.domain.LearnerLevel;
import com.lyreo.gamification.domain.XpTransaction;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcXpLedger implements XpLedger {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcXpLedger(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public XpTransaction append(XpTransaction transaction, int xpPerLevel) {
        int normalizedXpPerLevel = Math.max(1, xpPerLevel);
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
            jdbc.update(
                """
                INSERT INTO learner_level(learner_id, xp, level, updated_at)
                VALUES (
                    :learnerId,
                    GREATEST(0, :amount),
                    GREATEST(1, (GREATEST(0, :amount) / :xpPerLevel)::integer + 1),
                    now()
                )
                ON CONFLICT(learner_id) DO UPDATE SET
                    xp = GREATEST(0, learner_level.xp + :amount),
                    level = GREATEST(1, (GREATEST(0, learner_level.xp + :amount) / :xpPerLevel)::integer + 1),
                    updated_at = now()
                """,
                Map.of(
                    "learnerId", transaction.learnerId(),
                    "amount", transaction.amount(),
                    "xpPerLevel", normalizedXpPerLevel
                )
            );
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
