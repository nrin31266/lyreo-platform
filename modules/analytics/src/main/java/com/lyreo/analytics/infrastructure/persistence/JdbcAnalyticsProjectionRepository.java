package com.lyreo.analytics.infrastructure.persistence;

import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AnalyticsOverview;
import com.lyreo.analytics.domain.AnalyticsOverview.ActivityWindow;
import com.lyreo.analytics.domain.AnalyticsOverview.SkillSummary;
import com.lyreo.analytics.domain.AnalyticsOverview.WeaknessSummary;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL projection adapter for mobile Home/Progress analytics. */
public final class JdbcAnalyticsProjectionRepository implements AnalyticsProjectionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAnalyticsProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordActivity(UUID learnerId, String type, int score, Instant occurredAt) {
        // An activity completion is a skill sample, not a completed lesson. Lesson counts are
        // projected from LessonCompletedEvent to avoid counting multi-activity lessons twice.
        upsertSkill(learnerId, skillFor(type), score);
    }

    @Override
    public void recordLessonCompleted(UUID learnerId, Instant occurredAt) {
        jdbc.update(
            """
            INSERT INTO learner_daily_activity(learner_id, activity_date, lesson_count)
            VALUES(:learnerId, :activityDate, 1)
            ON CONFLICT(learner_id, activity_date) DO UPDATE SET
                lesson_count = learner_daily_activity.lesson_count + 1
            """,
            dailyParams(learnerId, occurredAt)
        );
    }

    @Override
    public void recordVocabularyReview(UUID learnerId, boolean correct, Instant occurredAt) {
        jdbc.update(
            """
            INSERT INTO learner_daily_activity(learner_id, activity_date, vocabulary_review_count)
            VALUES(:learnerId, :activityDate, 1)
            ON CONFLICT(learner_id, activity_date) DO UPDATE SET
                vocabulary_review_count = learner_daily_activity.vocabulary_review_count + 1
            """,
            dailyParams(learnerId, occurredAt)
        );
        upsertSkill(learnerId, "VOCABULARY", correct ? 100 : 0);
    }

    @Override
    public void recordVocabularyUnit(UUID learnerId, int correctCount, int totalCount, Instant occurredAt) {
        if (totalCount > 0) {
            int score = Math.max(0, Math.min(100, (int) Math.round(correctCount * 100d / totalCount)));
            upsertSkill(learnerId, "VOCABULARY", score);
        }
    }

    @Override
    public void recordGrammarAnswer(UUID learnerId, boolean correct, Instant occurredAt) {
        jdbc.update(
            """
            INSERT INTO learner_daily_activity(learner_id, activity_date, grammar_answer_count)
            VALUES(:learnerId, :activityDate, 1)
            ON CONFLICT(learner_id, activity_date) DO UPDATE SET
                grammar_answer_count = learner_daily_activity.grammar_answer_count + 1
            """,
            dailyParams(learnerId, occurredAt)
        );
        upsertSkill(learnerId, "GRAMMAR", correct ? 100 : 0);
    }

    @Override
    public void recordToeic(
        UUID learnerId,
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore,
        Instant occurredAt
    ) {
        int questionCount = Math.max(0, listeningTotal) + Math.max(0, readingTotal);
        if (questionCount > 0) {
            var params = new java.util.HashMap<String, Object>(dailyParams(learnerId, occurredAt));
            params.put("questionCount", questionCount);
            jdbc.update(
                """
                INSERT INTO learner_daily_activity(
                    learner_id,
                    activity_date,
                    toeic_question_count
                ) VALUES (
                    :learnerId,
                    :activityDate,
                    :questionCount
                )
                ON CONFLICT(learner_id, activity_date) DO UPDATE SET
                    toeic_question_count = learner_daily_activity.toeic_question_count
                        + excluded.toeic_question_count
                """,
                params
            );
        }

        int listeningPercent = normalizedToeicPercent(
            listeningCorrect,
            listeningTotal,
            listeningScaledScore
        );
        int readingPercent = normalizedToeicPercent(
            readingCorrect,
            readingTotal,
            readingScaledScore
        );

        if (listeningTotal > 0 || listeningScaledScore != null) {
            upsertSkill(learnerId, "TOEIC_LISTENING", listeningPercent);
        }
        if (readingTotal > 0 || readingScaledScore != null) {
            upsertSkill(learnerId, "TOEIC_READING", readingPercent);
        }
    }

    @Override
    public void recordWeaknessSample(
        UUID learnerId,
        String weaknessType,
        String weaknessKey,
        int errorScore,
        boolean insertIfMissing
    ) {
        int boundedError = Math.max(0, Math.min(100, errorScore));
        Map<String, Object> params = Map.of(
            "learnerId", learnerId,
            "weaknessType", weaknessType,
            "weaknessKey", weaknessKey,
            "errorScore", boundedError
        );
        if (insertIfMissing) {
            jdbc.update(
                """
                INSERT INTO learner_weakness(
                    learner_id, weakness_type, weakness_key, severity, sample_count, updated_at
                ) VALUES (
                    :learnerId, :weaknessType, :weaknessKey, :errorScore, 1, now()
                )
                ON CONFLICT(learner_id, weakness_type, weakness_key) DO UPDATE SET
                    severity = (
                        (learner_weakness.severity * learner_weakness.sample_count) + excluded.severity
                    ) / (learner_weakness.sample_count + 1),
                    sample_count = learner_weakness.sample_count + 1,
                    updated_at = now()
                """,
                params
            );
        } else {
            jdbc.update(
                """
                UPDATE learner_weakness
                SET severity = ((severity * sample_count) + :errorScore) / (sample_count + 1),
                    sample_count = sample_count + 1,
                    updated_at = now()
                WHERE learner_id = :learnerId
                  AND weakness_type = :weaknessType
                  AND weakness_key = :weaknessKey
                """,
                params
            );
        }
    }

    @Override
    public AnalyticsOverview loadOverview(UUID learnerId, LocalDate todayUtc) {
        Boolean hasHistory = jdbc.queryForObject(
            """
            SELECT (
                EXISTS (SELECT 1 FROM learner_daily_activity WHERE learner_id = :learnerId)
                OR EXISTS (SELECT 1 FROM learner_skill_summary WHERE learner_id = :learnerId)
                OR EXISTS (SELECT 1 FROM learner_weakness WHERE learner_id = :learnerId)
            )
            """,
            Map.of("learnerId", learnerId),
            Boolean.class
        );
        if (!Boolean.TRUE.equals(hasHistory)) {
            return AnalyticsOverview.empty(todayUtc);
        }

        ActivityWindow todayWindow = loadWindow(learnerId, todayUtc, todayUtc);
        ActivityWindow weekWindow = loadWindow(learnerId, todayUtc.minusDays(6), todayUtc);

        List<SkillSummary> skills = jdbc.query(
            """
            SELECT skill_code, score, sample_count, updated_at
            FROM learner_skill_summary
            WHERE learner_id = :learnerId
            ORDER BY skill_code ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new SkillSummary(
                rs.getString("skill_code"),
                rs.getBigDecimal("score"),
                rs.getInt("sample_count"),
                rs.getTimestamp("updated_at").toInstant()
            )
        );

        List<WeaknessSummary> weaknesses = jdbc.query(
            """
            SELECT weakness_type, weakness_key, severity, sample_count, updated_at
            FROM learner_weakness
            WHERE learner_id = :learnerId
              AND severity > 0
            ORDER BY severity DESC, sample_count DESC, weakness_type ASC, weakness_key ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new WeaknessSummary(
                rs.getString("weakness_type"),
                rs.getString("weakness_key"),
                rs.getBigDecimal("severity"),
                rs.getInt("sample_count"),
                rs.getTimestamp("updated_at").toInstant()
            )
        );

        return new AnalyticsOverview(true, todayWindow, weekWindow, skills, weaknesses);
    }

    @Override
    public AdminAnalyticsSummary summarizePlatform(LocalDate todayUtc) {
        LocalDate fromDate = todayUtc.minusDays(6);
        var totals = jdbc.queryForObject(
            """
            SELECT
                COUNT(DISTINCT CASE WHEN activity_date = :today THEN learner_id END)::int AS active_today,
                COUNT(DISTINCT learner_id)::int AS active_7d,
                COALESCE(SUM(study_seconds), 0)::bigint AS study_seconds,
                COALESCE(SUM(lesson_count), 0)::int AS lessons_7d,
                COALESCE(SUM(vocabulary_review_count), 0)::int AS vocab_7d,
                COALESCE(SUM(grammar_answer_count), 0)::int AS grammar_7d,
                COALESCE(SUM(toeic_question_count), 0)::int AS toeic_7d
            FROM learner_daily_activity
            WHERE activity_date BETWEEN :fromDate AND :today
            """,
            Map.of("fromDate", Date.valueOf(fromDate), "today", Date.valueOf(todayUtc)),
            (rs, rowNum) -> new int[] {
                rs.getInt("active_today"),
                rs.getInt("active_7d"),
                (int) Math.min(Integer.MAX_VALUE, rs.getLong("study_seconds")),
                rs.getInt("lessons_7d"),
                rs.getInt("vocab_7d"),
                rs.getInt("grammar_7d"),
                rs.getInt("toeic_7d")
            }
        );
        Long studySecondsLong = jdbc.queryForObject(
            """
            SELECT COALESCE(SUM(study_seconds), 0)::bigint
            FROM learner_daily_activity
            WHERE activity_date BETWEEN :fromDate AND :today
            """,
            Map.of("fromDate", Date.valueOf(fromDate), "today", Date.valueOf(todayUtc)),
            Long.class
        );
        List<AdminAnalyticsSummary.DailyAggregate> dailySeries = jdbc.query(
            """
            SELECT
                activity_date,
                COUNT(DISTINCT learner_id)::int AS active_learners,
                COALESCE(SUM(study_seconds), 0)::bigint AS study_seconds,
                COALESCE(SUM(lesson_count), 0)::int AS lesson_count,
                COALESCE(SUM(vocabulary_review_count), 0)::int AS vocabulary_review_count,
                COALESCE(SUM(grammar_answer_count), 0)::int AS grammar_answer_count,
                COALESCE(SUM(toeic_question_count), 0)::int AS toeic_question_count
            FROM learner_daily_activity
            WHERE activity_date BETWEEN :fromDate AND :today
            GROUP BY activity_date
            ORDER BY activity_date ASC
            """,
            Map.of("fromDate", Date.valueOf(fromDate), "today", Date.valueOf(todayUtc)),
            (rs, rowNum) -> new AdminAnalyticsSummary.DailyAggregate(
                rs.getDate("activity_date").toLocalDate(),
                rs.getInt("active_learners"),
                rs.getLong("study_seconds"),
                rs.getInt("lesson_count"),
                rs.getInt("vocabulary_review_count"),
                rs.getInt("grammar_answer_count"),
                rs.getInt("toeic_question_count")
            )
        );
        List<AdminAnalyticsSummary.WeaknessHotspot> topWeaknesses = jdbc.query(
            """
            SELECT
                weakness_type,
                weakness_key,
                COUNT(DISTINCT learner_id)::int AS affected_learners,
                ROUND(AVG(severity), 2) AS avg_severity,
                COALESCE(SUM(sample_count), 0)::int AS total_samples,
                MAX(updated_at) AS last_updated_at
            FROM learner_weakness
            WHERE severity > 0
            GROUP BY weakness_type, weakness_key
            ORDER BY avg_severity DESC, affected_learners DESC, weakness_type ASC, weakness_key ASC
            LIMIT 12
            """,
            Map.of(),
            (rs, rowNum) -> new AdminAnalyticsSummary.WeaknessHotspot(
                rs.getString("weakness_type"),
                rs.getString("weakness_key"),
                rs.getInt("affected_learners"),
                rs.getBigDecimal("avg_severity"),
                rs.getInt("total_samples"),
                rs.getTimestamp("last_updated_at").toInstant()
            )
        );
        return new AdminAnalyticsSummary(
            totals[0],
            totals[1],
            studySecondsLong != null ? studySecondsLong : 0L,
            totals[3],
            totals[4],
            totals[5],
            totals[6],
            dailySeries,
            topWeaknesses
        );
    }

    private ActivityWindow loadWindow(UUID learnerId, LocalDate fromDate, LocalDate toDate) {
        return jdbc.queryForObject(
            """
            SELECT
                COUNT(*)::int AS active_days,
                COALESCE(SUM(study_seconds), 0)::bigint AS study_seconds,
                COALESCE(SUM(lesson_count), 0)::int AS lesson_count,
                COALESCE(SUM(vocabulary_review_count), 0)::int AS vocabulary_review_count,
                COALESCE(SUM(grammar_answer_count), 0)::int AS grammar_answer_count,
                COALESCE(SUM(toeic_question_count), 0)::int AS toeic_question_count
            FROM learner_daily_activity
            WHERE learner_id = :learnerId
              AND activity_date BETWEEN :fromDate AND :toDate
            """,
            Map.of(
                "learnerId", learnerId,
                "fromDate", Date.valueOf(fromDate),
                "toDate", Date.valueOf(toDate)
            ),
            (rs, rowNum) -> new ActivityWindow(
                fromDate,
                toDate,
                rs.getInt("active_days"),
                rs.getLong("study_seconds"),
                rs.getInt("lesson_count"),
                rs.getInt("vocabulary_review_count"),
                rs.getInt("grammar_answer_count"),
                rs.getInt("toeic_question_count")
            )
        );
    }

    private static Map<String, Object> dailyParams(UUID learnerId, Instant occurredAt) {
        return Map.of(
            "learnerId", learnerId,
            "activityDate", occurredAt.atZone(ZoneOffset.UTC).toLocalDate()
        );
    }

    private static int normalizedToeicPercent(int correct, int total, Integer scaledScore) {
        if (scaledScore != null) {
            // TOEIC section scale is 5..495. Normalize only when a curated conversion supplied it.
            return Math.max(
                0,
                Math.min(100, (int) Math.round((scaledScore - 5) * 100d / 490d))
            );
        }
        if (total <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(100, (int) Math.round(correct * 100d / total)));
    }

    private void upsertSkill(UUID learnerId, String skillCode, int sampleScore) {
        jdbc.update(
            """
            INSERT INTO learner_skill_summary(
                learner_id,
                skill_code,
                score,
                sample_count,
                updated_at
            ) VALUES (
                :learnerId,
                :skillCode,
                :sampleScore,
                1,
                now()
            )
            ON CONFLICT(learner_id, skill_code) DO UPDATE SET
                score = (
                    (learner_skill_summary.score * learner_skill_summary.sample_count)
                    + excluded.score
                ) / (learner_skill_summary.sample_count + 1),
                sample_count = learner_skill_summary.sample_count + 1,
                updated_at = now()
            """,
            Map.of(
                "learnerId", learnerId,
                "skillCode", skillCode,
                "sampleScore", sampleScore
            )
        );
    }

    private static String skillFor(String activityType) {
        return switch (activityType) {
            case "SHADOWING" -> "SPEAKING";
            case "DICTATION" -> "LISTENING";
            case "GRAMMAR_PRACTICE" -> "GRAMMAR";
            case "VOCABULARY_PRACTICE" -> "VOCABULARY";
            default -> "GENERAL";
        };
    }
}
