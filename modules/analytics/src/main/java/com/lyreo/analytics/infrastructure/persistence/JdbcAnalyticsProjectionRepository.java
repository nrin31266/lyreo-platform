package com.lyreo.analytics.infrastructure.persistence;

import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AdminDashboardSummary;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile.DailyActivityPoint;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile.EnrolledPathProgress;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile.PracticeBreakdown;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile.UnlockedBadge;
import com.lyreo.analytics.domain.AnalyticsOverview;
import com.lyreo.analytics.domain.AnalyticsOverview.ActivityWindow;
import com.lyreo.analytics.domain.AnalyticsOverview.SkillSummary;
import com.lyreo.analytics.domain.AnalyticsOverview.WeaknessSummary;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

        return new AnalyticsOverview(true, todayWindow, weekWindow, skills, loadWeaknesses(learnerId));
    }

    private List<WeaknessSummary> loadWeaknesses(UUID learnerId) {
        return jdbc.query(
            """
            SELECT
                lw.weakness_type,
                lw.weakness_key,
                COALESCE(gt.label, vp.title_vi || ' · ' || vu.title, hw.identity_form, vue.term, vc.term) AS label,
                COALESCE(gt.label_en, vp.title_en || ' · ' || vu.title, hw.identity_form, vue.term, vc.term) AS label_en,
                lw.severity,
                lw.sample_count,
                lw.updated_at
            FROM learner_weakness lw
            LEFT JOIN LATERAL (
                SELECT COALESCE(NULLIF(gtv.title_vi, ''), gtv.title_en) AS label, gtv.title_en AS label_en
                FROM grammar_topic_version gtv
                WHERE gtv.catalog_id::text = lw.weakness_key
                ORDER BY gtv.id DESC
                LIMIT 1
            ) gt ON lw.weakness_type = 'GRAMMAR_TOPIC'
            LEFT JOIN vocabulary_unit vu ON lw.weakness_type = 'VOCABULARY_UNIT' AND vu.id::text = lw.weakness_key
            LEFT JOIN vocabulary_pack vp ON vp.id = vu.pack_id
            LEFT JOIN lexicon_headword hw ON lw.weakness_type = 'VOCABULARY' AND hw.id::text = lw.weakness_key
            LEFT JOIN vocabulary_unit_entry vue ON lw.weakness_type = 'VOCABULARY' AND vue.id::text = lw.weakness_key
            LEFT JOIN vocabulary_card vc ON lw.weakness_type = 'VOCABULARY' AND vc.id::text = lw.weakness_key
            WHERE lw.learner_id = :learnerId
              AND lw.severity > 0
            ORDER BY lw.severity DESC, lw.sample_count DESC, lw.weakness_type ASC, lw.weakness_key ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new WeaknessSummary(
                rs.getString("weakness_type"),
                rs.getString("weakness_key"),
                rs.getBigDecimal("severity"),
                rs.getInt("sample_count"),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("label"),
                rs.getString("label_en")
            )
        );
    }

    @Override
    public AdminLearnerStudyProfile loadAdminStudyProfile(UUID learnerId, LocalDate todayUtc) {
        ActivityWindow todayWindow = loadWindow(learnerId, todayUtc, todayUtc);
        ActivityWindow weekWindow = loadWindow(learnerId, todayUtc.minusDays(6), todayUtc);
        ActivityWindow monthWindow = loadWindow(learnerId, todayUtc.minusDays(29), todayUtc);

        List<DailyActivityPoint> dailySeries = jdbc.query(
            """
            SELECT
                d.day::date AS activity_date,
                COALESCE(lda.study_seconds, 0)::bigint AS study_seconds,
                COALESCE(lda.lesson_count, 0)::int AS lesson_count,
                COALESCE(lda.vocabulary_review_count, 0)::int AS vocabulary_review_count,
                COALESCE(lda.grammar_answer_count, 0)::int AS grammar_answer_count,
                COALESCE(lda.toeic_question_count, 0)::int AS toeic_question_count
            FROM generate_series(:fromDate::date, :toDate::date, interval '1 day') AS d(day)
            LEFT JOIN learner_daily_activity lda
                ON lda.learner_id = :learnerId
               AND lda.activity_date = d.day::date
            ORDER BY d.day ASC
            """,
            Map.of(
                "learnerId", learnerId,
                "fromDate", Date.valueOf(todayUtc.minusDays(13)),
                "toDate", Date.valueOf(todayUtc)
            ),
            (rs, rowNum) -> new DailyActivityPoint(
                rs.getDate("activity_date").toLocalDate(),
                rs.getLong("study_seconds"),
                rs.getInt("lesson_count"),
                rs.getInt("vocabulary_review_count"),
                rs.getInt("grammar_answer_count"),
                rs.getInt("toeic_question_count")
            )
        );

        List<SkillSummary> skills = jdbc.query(
            """
            SELECT skill_code, score, sample_count, updated_at
            FROM learner_skill_summary
            WHERE learner_id = :learnerId
            ORDER BY score DESC, skill_code ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new SkillSummary(
                rs.getString("skill_code"),
                rs.getBigDecimal("score"),
                rs.getInt("sample_count"),
                rs.getTimestamp("updated_at").toInstant()
            )
        );

        List<WeaknessSummary> weaknesses = loadWeaknesses(learnerId);

        List<EnrolledPathProgress> enrolledPaths = jdbc.query(
            """
            SELECT
                cp.id AS path_id,
                cp.name AS path_name,
                COALESCE(cp.level, '') AS level,
                ce.status,
                COUNT(DISTINCT ci.id)::int AS total_items,
                COUNT(DISTINCT CASE WHEN cip.status = 'COMPLETED' THEN ci.id END)::int AS completed_items,
                ce.started_at
            FROM curriculum_enrollment ce
            JOIN curriculum_path cp ON cp.id = ce.path_id
            LEFT JOIN curriculum_section cs ON cs.path_id = cp.id
            LEFT JOIN curriculum_item ci ON ci.section_id = cs.id
            LEFT JOIN curriculum_item_progress cip
                ON cip.item_id = ci.id
               AND cip.learner_id = ce.learner_id
            WHERE ce.learner_id = :learnerId
            GROUP BY cp.id, cp.name, cp.level, ce.status, ce.started_at
            ORDER BY ce.started_at DESC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new EnrolledPathProgress(
                UUID.fromString(rs.getString("path_id")),
                rs.getString("path_name"),
                rs.getString("level"),
                rs.getString("status"),
                rs.getInt("completed_items"),
                rs.getInt("total_items"),
                rs.getTimestamp("started_at").toInstant()
            )
        );

        List<UnlockedBadge> unlockedAchievements = jdbc.query(
            """
            SELECT
                ad.id AS achievement_id,
                ad.code,
                ad.title,
                COALESCE(ad.badge_tier, 'BRONZE') AS badge_tier,
                ad.badge_asset_id,
                la.unlocked_at
            FROM learner_achievement la
            JOIN achievement_definition ad ON ad.id = la.achievement_id
            WHERE la.learner_id = :learnerId
              AND la.unlocked_at IS NOT NULL
            ORDER BY la.unlocked_at DESC, ad.code ASC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> {
                String rawAsset = rs.getString("badge_asset_id");
                return new UnlockedBadge(
                    UUID.fromString(rs.getString("achievement_id")),
                    rs.getString("code"),
                    rs.getString("title"),
                    rs.getString("badge_tier"),
                    rawAsset != null ? UUID.fromString(rawAsset) : null,
                    rs.getTimestamp("unlocked_at").toInstant()
                );
            }
        );

        PracticeBreakdown breakdown = jdbc.queryForObject(
            """
            SELECT
                (SELECT COUNT(*) FROM lesson_progress WHERE learner_id = :learnerId AND status = 'COMPLETED')::int AS lessons_completed,
                (SELECT COUNT(*) FROM lesson_progress WHERE learner_id = :learnerId AND status = 'IN_PROGRESS')::int AS lessons_in_progress,
                (SELECT COUNT(*) FROM vocabulary_card WHERE learner_id = :learnerId AND suspended_at IS NULL)::int AS vocab_cards_total,
                (SELECT COUNT(*) FROM vocabulary_card WHERE learner_id = :learnerId AND suspended_at IS NULL AND (stability >= 5 OR (review_count >= 3 AND lapse_count = 0)))::int AS vocab_cards_mastered,
                grammar.answers::int AS grammar_attempts,
                COALESCE(ROUND(grammar.correct * 100.0 / NULLIF(grammar.answers, 0)), 0)::int AS grammar_accuracy_pct,
                toeic.attempts::int AS toeic_attempts,
                toeic.best_score AS best_toeic_score
            FROM (
                SELECT SUM(answers) AS answers, SUM(correct) AS correct FROM (
                    SELECT COUNT(*) AS answers, COUNT(*) FILTER (WHERE correct) AS correct
                    FROM grammar_practice_attempt WHERE learner_id = :learnerId
                    UNION ALL
                    SELECT COALESCE(SUM((result->>'total')::int), 0),
                           COALESCE(SUM((result->>'correct')::int), 0)
                    FROM assessment_session
                    WHERE learner_id = :learnerId AND domain = 'grammar' AND submitted_at IS NOT NULL
                ) grammar_sources
            ) grammar
            CROSS JOIN (
                SELECT COUNT(DISTINCT id) AS attempts, MAX(score) AS best_score
                FROM (
                    SELECT id,
                           CASE WHEN mode = 'FULL_TEST' THEN listening_score + reading_score END AS score
                    FROM toeic_attempt WHERE learner_id = :learnerId AND submitted_at IS NOT NULL
                    UNION ALL
                    SELECT id,
                           CASE WHEN (result->>'listeningTotal')::int = 100 AND (result->>'readingTotal')::int = 100
                               THEN (result->>'listeningScaledScore')::int + (result->>'readingScaledScore')::int END
                    FROM assessment_session
                    WHERE learner_id = :learnerId AND domain = 'toeic' AND submitted_at IS NOT NULL
                ) toeic_sources
            ) toeic
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> new PracticeBreakdown(
                rs.getInt("lessons_completed"),
                rs.getInt("lessons_in_progress"),
                rs.getInt("vocab_cards_total"),
                rs.getInt("vocab_cards_mastered"),
                rs.getInt("grammar_attempts"),
                rs.getInt("grammar_accuracy_pct"),
                rs.getInt("toeic_attempts"),
                (Integer) rs.getObject("best_toeic_score")
            )
        );

        boolean hasHistory =
            monthWindow.activeDays() > 0
                || !skills.isEmpty()
                || !weaknesses.isEmpty()
                || !enrolledPaths.isEmpty()
                || !unlockedAchievements.isEmpty()
                || (breakdown != null
                    && (breakdown.lessonsCompleted() > 0
                        || breakdown.lessonsInProgress() > 0
                        || breakdown.vocabCardsTotal() > 0
                        || breakdown.grammarAttempts() > 0
                        || breakdown.toeicAttempts() > 0));

        return new AdminLearnerStudyProfile(
            hasHistory,
            todayWindow,
            weekWindow,
            monthWindow,
            dailySeries,
            skills,
            weaknesses,
            enrolledPaths,
            unlockedAchievements,
            breakdown != null ? breakdown : new PracticeBreakdown(0, 0, 0, 0, 0, 0, 0, null)
        );
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

    @Override
    public AdminDashboardSummary loadAdminDashboard(LocalDate todayUtc, int days) {
        LocalDate fromDate = todayUtc.minusDays(days - 1L);
        LocalDate prevFromDate = fromDate.minusDays(days);
        LocalDate prevToDate = fromDate.minusDays(1);
        Instant periodStart = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant periodEndExclusive = todayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant prevStart = prevFromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant todayStart = todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant last24h = Instant.now().minusSeconds(86_400L);

        var kpiParams = new MapSqlParameterSource()
            .addValue("fromDate", Date.valueOf(fromDate))
            .addValue("today", Date.valueOf(todayUtc))
            .addValue("prevFromDate", Date.valueOf(prevFromDate))
            .addValue("prevToDate", Date.valueOf(prevToDate))
            .addValue("periodStart", Timestamp.from(periodStart))
            .addValue("periodEndExclusive", Timestamp.from(periodEndExclusive))
            .addValue("prevStart", Timestamp.from(prevStart))
            .addValue("todayStart", Timestamp.from(todayStart));

        AdminDashboardSummary.Kpis kpis = jdbc.queryForObject(
            """
            SELECT
                (SELECT COUNT(DISTINCT learner_id)::int FROM learner_daily_activity WHERE activity_date BETWEEN :fromDate AND :today) AS active_learners,
                (SELECT COUNT(DISTINCT learner_id)::int FROM learner_daily_activity WHERE activity_date BETWEEN :prevFromDate AND :prevToDate) AS prev_active_learners,
                (SELECT COUNT(DISTINCT learner_id)::int FROM learner_daily_activity WHERE activity_date = :today) AS active_today,
                (SELECT COUNT(*)::int FROM app_user) AS total_learners,
                (SELECT COUNT(*)::int FROM app_user WHERE created_at >= :periodStart AND created_at < :periodEndExclusive) AS new_learners,
                (SELECT COUNT(*)::int FROM app_user WHERE created_at >= :prevStart AND created_at < :periodStart) AS prev_new_learners,
                (SELECT COUNT(*)::int FROM learner_profile WHERE onboarding_completed_at IS NOT NULL) AS onboarded_learners,
                (SELECT COUNT(DISTINCT learner_id)::int FROM learner_plus_period WHERE starts_at <= now() AND (ends_at IS NULL OR ends_at > now())) AS active_plus,
                (SELECT COUNT(*)::int FROM learner_plus_period WHERE starts_at >= :periodStart AND starts_at < :periodEndExclusive) AS plus_started,
                COALESCE(curr.study_seconds, 0)::bigint AS study_seconds,
                COALESCE(prev.study_seconds, 0)::bigint AS prev_study_seconds,
                COALESCE(curr.lessons, 0)::int AS lessons_completed,
                COALESCE(curr.vocab, 0)::int AS vocabulary_reviews,
                COALESCE(curr.grammar, 0)::int AS grammar_answers,
                COALESCE(curr.toeic, 0)::int AS toeic_questions,
                COALESCE(curr.practice_total, 0)::int AS practice_total,
                COALESCE(prev.practice_total, 0)::int AS prev_practice_total,
                (SELECT COUNT(*)::int FROM chat_message WHERE role = 'USER' AND created_at >= :periodStart AND created_at < :periodEndExclusive) AS ai_tutor_turns,
                (SELECT COUNT(*)::int FROM chat_message WHERE role = 'USER' AND created_at >= :todayStart AND created_at < :periodEndExclusive) AS ai_tutor_turns_today,
                (SELECT COUNT(DISTINCT c.learner_id)::int FROM chat_conversation c JOIN chat_message m ON m.conversation_id = c.id WHERE m.role = 'USER' AND m.created_at >= :periodStart AND m.created_at < :periodEndExclusive) AS ai_tutor_active_learners,
                (SELECT COUNT(*)::bigint FROM chat_conversation) AS ai_tutor_total_conversations
            FROM (
                SELECT
                    COALESCE(SUM(study_seconds), 0) AS study_seconds,
                    COALESCE(SUM(lesson_count), 0) AS lessons,
                    COALESCE(SUM(vocabulary_review_count), 0) AS vocab,
                    COALESCE(SUM(grammar_answer_count), 0) AS grammar,
                    COALESCE(SUM(toeic_question_count), 0) AS toeic,
                    COALESCE(SUM(lesson_count + vocabulary_review_count + grammar_answer_count + toeic_question_count), 0) AS practice_total
                FROM learner_daily_activity
                WHERE activity_date BETWEEN :fromDate AND :today
            ) curr
            CROSS JOIN (
                SELECT
                    COALESCE(SUM(study_seconds), 0) AS study_seconds,
                    COALESCE(SUM(lesson_count + vocabulary_review_count + grammar_answer_count + toeic_question_count), 0) AS practice_total
                FROM learner_daily_activity
                WHERE activity_date BETWEEN :prevFromDate AND :prevToDate
            ) prev
            """,
            kpiParams,
            (rs, rowNum) -> new AdminDashboardSummary.Kpis(
                rs.getInt("active_learners"),
                rs.getInt("prev_active_learners"),
                rs.getInt("active_today"),
                rs.getInt("total_learners"),
                rs.getInt("new_learners"),
                rs.getInt("prev_new_learners"),
                rs.getInt("onboarded_learners"),
                rs.getInt("active_plus"),
                rs.getInt("plus_started"),
                rs.getLong("study_seconds"),
                rs.getLong("prev_study_seconds"),
                rs.getInt("lessons_completed"),
                rs.getInt("vocabulary_reviews"),
                rs.getInt("grammar_answers"),
                rs.getInt("toeic_questions"),
                rs.getInt("practice_total"),
                rs.getInt("prev_practice_total"),
                rs.getInt("ai_tutor_turns"),
                rs.getInt("ai_tutor_turns_today"),
                rs.getInt("ai_tutor_active_learners"),
                rs.getLong("ai_tutor_total_conversations")
            )
        );

        List<AdminDashboardSummary.DailyPoint> dailySeries = jdbc.query(
            """
            SELECT
                d.day::date AS activity_date,
                COALESCE(a.active_learners, 0)::int AS active_learners,
                COALESCE(u.new_learners, 0)::int AS new_learners,
                COALESCE(a.study_seconds, 0)::bigint AS study_seconds,
                COALESCE(a.lesson_count, 0)::int AS lesson_count,
                COALESCE(a.vocabulary_review_count, 0)::int AS vocabulary_review_count,
                COALESCE(a.grammar_answer_count, 0)::int AS grammar_answer_count,
                COALESCE(a.toeic_question_count, 0)::int AS toeic_question_count
            FROM generate_series(CAST(:fromDate AS date), CAST(:today AS date), interval '1 day') AS d(day)
            LEFT JOIN (
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
            ) a ON a.activity_date = d.day::date
            LEFT JOIN (
                SELECT (created_at AT TIME ZONE 'UTC')::date AS reg_date, COUNT(*)::int AS new_learners
                FROM app_user
                WHERE created_at >= :periodStart AND created_at < :periodEndExclusive
                GROUP BY (created_at AT TIME ZONE 'UTC')::date
            ) u ON u.reg_date = d.day::date
            ORDER BY d.day ASC
            """,
            kpiParams,
            (rs, rowNum) -> new AdminDashboardSummary.DailyPoint(
                rs.getDate("activity_date").toLocalDate(),
                rs.getInt("active_learners"),
                rs.getInt("new_learners"),
                rs.getLong("study_seconds"),
                rs.getInt("lesson_count"),
                rs.getInt("vocabulary_review_count"),
                rs.getInt("grammar_answer_count"),
                rs.getInt("toeic_question_count")
            )
        );

        AdminDashboardSummary.OperationsHealth operations = jdbc.queryForObject(
            """
            SELECT
                0 AS jobs_running,
                0 AS jobs_failed_24h,
                0 AS jobs_succeeded_24h,
                (SELECT COUNT(*)::int FROM ai_provider WHERE enabled = true) AS ai_enabled,
                (SELECT COUNT(*)::int FROM ai_provider WHERE enabled = true AND connection_status = 'HEALTHY') AS ai_healthy,
                (SELECT COUNT(*)::int FROM ai_provider WHERE enabled = true AND (connection_status IN ('FAILED', 'UNHEALTHY', 'ERROR') OR encrypted_api_key IS NULL)) AS ai_unhealthy,
                (SELECT COUNT(*)::int FROM ai_invocation WHERE created_at >= :last24h) AS ai_invocations_24h,
                (SELECT COUNT(*)::int FROM ai_invocation WHERE created_at >= :last24h AND status <> 'SUCCEEDED') AS ai_errors_24h,
                (SELECT COALESCE(ROUND(AVG(latency_ms))::bigint, 0) FROM ai_invocation WHERE created_at >= :last24h AND latency_ms IS NOT NULL) AS ai_avg_latency_24h,
                (SELECT COUNT(*)::int FROM app_user WHERE account_locked = true) AS locked_accounts,
                (SELECT COUNT(*)::int FROM app_user WHERE account_sync_pending = true) AS pending_sync,
                (SELECT COUNT(*)::int FROM learner_notification WHERE status = 'PUBLISHED' AND published_at > now()) AS scheduled_notifications
            """,
            new MapSqlParameterSource("last24h", Timestamp.from(last24h)),
            (rs, rowNum) -> new AdminDashboardSummary.OperationsHealth(
                rs.getInt("jobs_running"),
                rs.getInt("jobs_failed_24h"),
                rs.getInt("jobs_succeeded_24h"),
                rs.getInt("ai_enabled"),
                rs.getInt("ai_healthy"),
                rs.getInt("ai_unhealthy"),
                rs.getInt("ai_invocations_24h"),
                rs.getInt("ai_errors_24h"),
                rs.getLong("ai_avg_latency_24h"),
                rs.getInt("locked_accounts"),
                rs.getInt("pending_sync"),
                rs.getInt("scheduled_notifications")
            )
        );

        AdminDashboardSummary.ContentInventory contentInventory = jdbc.queryForObject(
            """
            SELECT
                (SELECT COUNT(*)::int FROM lesson l LEFT JOIN lesson_metadata lm ON lm.lesson_id = l.id WHERE lm.deleted_at IS NULL AND l.status = 'PUBLISHED') AS lessons_published,
                (SELECT COUNT(*)::int FROM lesson l LEFT JOIN lesson_metadata lm ON lm.lesson_id = l.id WHERE lm.deleted_at IS NULL AND l.status <> 'PUBLISHED') AS lessons_draft,
                (SELECT COUNT(*)::int FROM curriculum_path WHERE status = 'PUBLISHED') AS paths_published,
                (SELECT COUNT(*)::int FROM curriculum_path WHERE status <> 'PUBLISHED') AS paths_draft,
                (SELECT COUNT(*)::int FROM curriculum_enrollment WHERE status = 'ACTIVE') AS enrollments_active,
                (SELECT COUNT(*)::int FROM curriculum_enrollment WHERE status = 'COMPLETED') AS enrollments_completed,
                (SELECT COUNT(*)::int FROM vocabulary_pack vp WHERE vp.hidden = false AND (NOT EXISTS (SELECT 1 FROM dataset_active_release WHERE domain = 'vocabulary') OR vp.release_id = (SELECT release_id FROM dataset_active_release WHERE domain = 'vocabulary' LIMIT 1))) AS vocab_packs,
                (SELECT COUNT(*)::int FROM vocabulary_unit vu JOIN vocabulary_pack vp ON vp.id = vu.pack_id WHERE vp.hidden = false AND (NOT EXISTS (SELECT 1 FROM dataset_active_release WHERE domain = 'vocabulary') OR vp.release_id = (SELECT release_id FROM dataset_active_release WHERE domain = 'vocabulary' LIMIT 1))) AS vocab_units,
                (SELECT COUNT(*)::int FROM grammar_topic_catalog WHERE publication_status = 'PUBLISHED') AS grammar_topics,
                (SELECT COUNT(*)::int FROM toeic_test_catalog WHERE publication_status = 'PUBLISHED') AS toeic_tests,
                (SELECT COUNT(*)::int FROM lexicon_headword) AS lexicon_headwords
            """,
            Map.of(),
            (rs, rowNum) -> new AdminDashboardSummary.ContentInventory(
                rs.getInt("lessons_published"),
                rs.getInt("lessons_draft"),
                rs.getInt("paths_published"),
                rs.getInt("paths_draft"),
                rs.getInt("enrollments_active"),
                rs.getInt("enrollments_completed"),
                rs.getInt("vocab_packs"),
                rs.getInt("vocab_units"),
                rs.getInt("grammar_topics"),
                rs.getInt("toeic_tests"),
                rs.getInt("lexicon_headwords")
            )
        );

        List<AdminDashboardSummary.CurriculumPathMetric> topPaths = jdbc.query(
            """
            SELECT
                cp.id AS path_id,
                cp.code,
                cp.name,
                COALESCE(cp.level, '') AS level,
                cp.status,
                COUNT(DISTINCT ce.learner_id)::int AS enrolled_learners,
                COUNT(DISTINCT CASE WHEN ce.status = 'COMPLETED' THEN ce.learner_id END)::int AS completed_learners,
                COALESCE(
                    CASE
                        WHEN COALESCE(pt.total_items, 0) = 0 OR COUNT(DISTINCT ce.learner_id) = 0 THEN 0
                        ELSE LEAST(
                            100,
                            ROUND(
                                (
                                    SELECT COUNT(*)::numeric
                                    FROM curriculum_item_progress cip
                                    JOIN curriculum_item ci2 ON ci2.id = cip.item_id
                                    JOIN curriculum_section cs2 ON cs2.id = ci2.section_id
                                    JOIN curriculum_enrollment ce2
                                      ON ce2.learner_id = cip.learner_id AND ce2.path_id = cp.id
                                    WHERE cs2.path_id = cp.id AND cip.status = 'COMPLETED'
                                ) * 100.0 / (pt.total_items * COUNT(DISTINCT ce.learner_id))
                            )::int
                        )
                    END,
                    0
                ) AS avg_completion_percent
            FROM curriculum_path cp
            LEFT JOIN curriculum_enrollment ce ON ce.path_id = cp.id
            LEFT JOIN (
                SELECT cs.path_id, COUNT(ci.id)::int AS total_items
                FROM curriculum_section cs
                JOIN curriculum_item ci ON ci.section_id = cs.id
                GROUP BY cs.path_id
            ) pt ON pt.path_id = cp.id
            WHERE cp.status = 'PUBLISHED'
            GROUP BY cp.id, cp.code, cp.name, cp.level, cp.status, cp.created_at, pt.total_items
            ORDER BY enrolled_learners DESC, completed_learners DESC, cp.created_at DESC
            LIMIT 5
            """,
            Map.of(),
            (rs, rowNum) -> new AdminDashboardSummary.CurriculumPathMetric(
                rs.getObject("path_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("level"),
                rs.getString("status"),
                rs.getInt("enrolled_learners"),
                rs.getInt("completed_learners"),
                rs.getInt("avg_completion_percent")
            )
        );

        List<AdminAnalyticsSummary.WeaknessHotspot> topWeaknesses = jdbc.query(
            """
            SELECT
                agg.*,
                COALESCE(gt.label, vp.title_vi || ' · ' || vu.title, hw.identity_form, vue.term, vc.term) AS label,
                COALESCE(gt.label_en, vp.title_en || ' · ' || vu.title, hw.identity_form, vue.term, vc.term) AS label_en
            FROM (
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
                LIMIT 8
            ) agg
            LEFT JOIN LATERAL (
                SELECT COALESCE(NULLIF(gtv.title_vi, ''), gtv.title_en) AS label, gtv.title_en AS label_en
                FROM grammar_topic_version gtv
                WHERE gtv.catalog_id::text = agg.weakness_key
                ORDER BY gtv.id DESC
                LIMIT 1
            ) gt ON agg.weakness_type = 'GRAMMAR_TOPIC'
            LEFT JOIN vocabulary_unit vu ON agg.weakness_type = 'VOCABULARY_UNIT' AND vu.id::text = agg.weakness_key
            LEFT JOIN vocabulary_pack vp ON vp.id = vu.pack_id
            LEFT JOIN lexicon_headword hw ON agg.weakness_type = 'VOCABULARY' AND hw.id::text = agg.weakness_key
            LEFT JOIN vocabulary_unit_entry vue ON agg.weakness_type = 'VOCABULARY' AND vue.id::text = agg.weakness_key
            LEFT JOIN vocabulary_card vc ON agg.weakness_type = 'VOCABULARY' AND vc.id::text = agg.weakness_key
            ORDER BY agg.avg_severity DESC, agg.affected_learners DESC, agg.weakness_type ASC, agg.weakness_key ASC
            """,
            Map.of(),
            (rs, rowNum) -> new AdminAnalyticsSummary.WeaknessHotspot(
                rs.getString("weakness_type"),
                rs.getString("weakness_key"),
                rs.getInt("affected_learners"),
                rs.getBigDecimal("avg_severity"),
                rs.getInt("total_samples"),
                rs.getTimestamp("last_updated_at").toInstant(),
                rs.getString("label"),
                rs.getString("label_en")
            )
        );

        return new AdminDashboardSummary(
            new AdminDashboardSummary.Period(days, fromDate, todayUtc),
            kpis,
            dailySeries,
            operations,
            contentInventory,
            topPaths,
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
