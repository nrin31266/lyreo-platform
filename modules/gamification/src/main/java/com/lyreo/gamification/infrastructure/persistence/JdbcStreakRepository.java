package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.StreakRepository;
import com.lyreo.gamification.domain.LearnerStreak;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcStreakRepository implements StreakRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStreakRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockLearner(UUID learnerId) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            Map.of("lockKey", "gamification-streak:" + learnerId),
            Object.class
        );
    }

    @Override
    public boolean recordStudyDate(UUID learnerId, LocalDate studyDate, UUID firstFactId) {
        int inserted = jdbc.update(
            """
            INSERT INTO learner_streak_day(learner_id, study_date, first_fact_id)
            VALUES (:learnerId, :studyDate, :firstFactId)
            ON CONFLICT (learner_id, study_date) DO NOTHING
            """,
            Map.of(
                "learnerId", learnerId,
                "studyDate", Date.valueOf(studyDate),
                "firstFactId", firstFactId
            )
        );
        return inserted == 1;
    }

    @Override
    public List<LocalDate> listStudyDates(UUID learnerId) {
        return jdbc.query(
            """
            SELECT study_date
            FROM learner_streak_day
            WHERE learner_id = :learnerId
            ORDER BY study_date ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> rs.getDate("study_date").toLocalDate()
        );
    }

    @Override
    public LearnerStreak upsertStreak(
        UUID learnerId,
        int currentStreak,
        int longestStreak,
        LocalDate lastStudyDate
    ) {
        return jdbc.queryForObject(
            """
            INSERT INTO learner_streak(
                learner_id, current_streak, longest_streak, last_study_date, updated_at
            ) VALUES (
                :learnerId, :currentStreak, :longestStreak, :lastStudyDate, now()
            )
            ON CONFLICT (learner_id) DO UPDATE SET
                current_streak = excluded.current_streak,
                longest_streak = GREATEST(learner_streak.longest_streak, excluded.longest_streak),
                last_study_date = excluded.last_study_date,
                updated_at = now()
            RETURNING learner_id, current_streak, longest_streak, last_study_date, updated_at
            """,
            Map.of(
                "learnerId", learnerId,
                "currentStreak", currentStreak,
                "longestStreak", longestStreak,
                "lastStudyDate", Date.valueOf(lastStudyDate)
            ),
            (rs, rowNum) -> new LearnerStreak(
                rs.getObject("learner_id", UUID.class),
                rs.getInt("current_streak"),
                rs.getInt("longest_streak"),
                rs.getDate("last_study_date").toLocalDate(),
                rs.getTimestamp("updated_at").toInstant()
            )
        );
    }

    @Override
    public LearnerStreak findStreak(UUID learnerId) {
        return jdbc.query(
            """
            SELECT learner_id, current_streak, longest_streak, last_study_date, updated_at
            FROM learner_streak
            WHERE learner_id = :learnerId
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> {
                Date lastDate = rs.getDate("last_study_date");
                return new LearnerStreak(
                    rs.getObject("learner_id", UUID.class),
                    rs.getInt("current_streak"),
                    rs.getInt("longest_streak"),
                    lastDate == null ? null : lastDate.toLocalDate(),
                    rs.getTimestamp("updated_at").toInstant()
                );
            }
        ).stream().findFirst().orElseGet(() -> LearnerStreak.empty(learnerId));
    }
}
