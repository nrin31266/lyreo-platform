package com.lyreo.learner.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.learner.application.LearnerProfileRepository;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Explicit JDBC adapter for the small Learner profile/preferences aggregate.
 *
 * <p>The inner Learner model stays free of persistence annotations. Preferences are one
 * versionable document because they are UI/learning-policy choices that evolve more often
 * than relational identity fields.</p>
 */
public final class JdbcLearnerProfileRepository implements LearnerProfileRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLearnerProfileRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<LearnerProfile> findByLearnerId(UUID learnerId) {
        return jdbc.query("""
            SELECT learner_id,
                   display_name,
                   current_level,
                   goal,
                   daily_minutes,
                   focus_area,
                   preferences_json::text
              FROM learner_profile
             WHERE learner_id = :id
            """, Map.of("id", learnerId), (rs, rowNum) -> new LearnerProfile(
                rs.getObject("learner_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("current_level"),
                rs.getString("goal"),
                rs.getObject("daily_minutes", Integer.class),
                rs.getString("focus_area"),
                readPreferences(rs.getString("preferences_json"))
            ))
            .stream()
            .findFirst();
    }

    @Override
    public LearnerProfile save(LearnerProfile profile) {
        try {
            LearnerPreferences effectivePreferences = profile.preferences() == null
                ? LearnerPreferences.defaults()
                : profile.preferences();
            String preferencesJson = mapper.writeValueAsString(effectivePreferences);

            jdbc.update("""
                INSERT INTO learner_profile(
                  learner_id,
                  display_name,
                  current_level,
                  goal,
                  daily_minutes,
                  focus_area,
                  preferences_json,
                  onboarding_completed_at,
                  updated_at
                ) VALUES (
                  :id,
                  :name,
                  :level,
                  :goal,
                  :minutes,
                  :focus,
                  CAST(:preferences AS jsonb),
                  now(),
                  now()
                )
                ON CONFLICT(learner_id) DO UPDATE SET
                  display_name = excluded.display_name,
                  current_level = excluded.current_level,
                  goal = excluded.goal,
                  daily_minutes = excluded.daily_minutes,
                  focus_area = excluded.focus_area,
                  preferences_json = excluded.preferences_json,
                  onboarding_completed_at = COALESCE(
                    learner_profile.onboarding_completed_at,
                    now()
                  ),
                  updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", profile.learnerId())
                .addValue("name", profile.displayName())
                .addValue("level", profile.currentLevel())
                .addValue("goal", profile.goal())
                .addValue("minutes", profile.dailyMinutes())
                .addValue("focus", profile.focusArea())
                .addValue("preferences", preferencesJson));

            return findByLearnerId(profile.learnerId()).orElseThrow();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save learner profile", exception);
        }
    }

    @Override
    public LearnerPreferences savePreferences(
        UUID learnerId,
        LearnerPreferences preferences
    ) {
        try {
            String json = mapper.writeValueAsString(preferences);
            jdbc.update("""
                INSERT INTO learner_profile(learner_id, preferences_json, updated_at)
                VALUES (:id, CAST(:preferences AS jsonb), now())
                ON CONFLICT(learner_id) DO UPDATE SET
                  preferences_json = excluded.preferences_json,
                  updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", learnerId)
                .addValue("preferences", json));

            return findByLearnerId(learnerId)
                .map(LearnerProfile::preferences)
                .orElse(preferences);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to save learner preferences", exception);
        }
    }

    private LearnerPreferences readPreferences(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return LearnerPreferences.defaults();
        }

        try {
            return mapper.readValue(json, LearnerPreferences.class);
        } catch (Exception ignored) {
            // Preferences are soft user configuration. A malformed/legacy document should not
            // make login/Home unusable; fall back to defaults and let a future migration repair it.
            return LearnerPreferences.defaults();
        }
    }
}
