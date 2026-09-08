package com.lyreo.analytics.infrastructure;

import com.lyreo.analytics.application.AnalyticsProjectionRepository;
import java.time.Instant;
import java.time.ZoneOffset;
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
        if (total <= 0) return 0;
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
