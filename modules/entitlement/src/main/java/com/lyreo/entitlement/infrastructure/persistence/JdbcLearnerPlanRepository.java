package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.application.port.LearnerPlanRepository;
import com.lyreo.entitlement.domain.LearnerPlan;
import com.lyreo.entitlement.domain.LearnerPlan.Change;
import com.lyreo.entitlement.domain.LearnerPlan.Tier;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLearnerPlanRepository implements LearnerPlanRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLearnerPlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isPlus(UUID learnerId, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM learner_plus_period
                WHERE learner_id = :learnerId AND starts_at <= :now AND (ends_at IS NULL OR ends_at > :now)
            )
            """, Map.of("learnerId", learnerId, "now", Timestamp.from(now.truncatedTo(ChronoUnit.MICROS))), Boolean.class));
    }

    @Override
    public void lockPlan(UUID learnerId) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            Map.of("lockKey", "learner-plan:" + learnerId),
            Object.class
        );
    }

    @Override
    public LearnerPlan readPlan(UUID learnerId, Instant now) {
        return jdbc.query("""
            WITH periods AS (
                SELECT starts_at, ends_at, started_by, ended_by
                FROM learner_plus_period
                WHERE learner_id = :learnerId AND starts_at <= :now
                  AND (ends_at IS NULL OR ends_at > starts_at)
            )
            SELECT 'PLUS' AS tier, starts_at AS changed_at, started_by AS actor, ends_at AS expiry
            FROM periods
            UNION ALL
            SELECT 'FREE', ends_at, ended_by, NULL FROM periods WHERE ends_at <= :now
            ORDER BY changed_at DESC, tier DESC
            LIMIT 30
            """, Map.of("learnerId", learnerId, "now", Timestamp.from(now)), rs -> {
                if (!rs.next()) {
                    return new LearnerPlan(Tier.FREE, null, List.of());
                }
                Tier tier = Tier.valueOf(rs.getString("tier"));
                Timestamp expiry = rs.getTimestamp("expiry");
                var history = new ArrayList<Change>();
                do {
                    history.add(new Change(
                        Tier.valueOf(rs.getString("tier")),
                        rs.getTimestamp("changed_at").toInstant(),
                        rs.getString("actor")
                    ));
                } while (rs.next());
                return new LearnerPlan(tier, expiry == null ? null : expiry.toInstant(), List.copyOf(history));
            });
    }

    @Override
    public void startPlus(UUID learnerId, Instant now, Instant endsAt, String actor) {
        jdbc.update("""
            INSERT INTO learner_plus_period (learner_id, starts_at, ends_at, started_by)
            VALUES (:learnerId, :now, :endsAt, :actor)
            """, new MapSqlParameterSource("learnerId", learnerId)
                .addValue("now", Timestamp.from(now))
                .addValue("endsAt", endsAt == null ? null : Timestamp.from(endsAt))
                .addValue("actor", actor));
    }

    @Override
    public void endPlus(UUID learnerId, Instant now, String actor) {
        jdbc.update("""
            UPDATE learner_plus_period SET ends_at = :now, ended_by = :actor
            WHERE learner_id = :learnerId AND starts_at <= :now AND (ends_at IS NULL OR ends_at > :now)
            """, Map.of("learnerId", learnerId, "now", Timestamp.from(now), "actor", actor));
    }

    @Override
    public void cancelScheduledPlus(UUID learnerId, Instant now) {
        jdbc.update("""
            DELETE FROM learner_plus_period WHERE learner_id = :learnerId AND starts_at > :now
            """, Map.of("learnerId", learnerId, "now", Timestamp.from(now)));
    }
}
