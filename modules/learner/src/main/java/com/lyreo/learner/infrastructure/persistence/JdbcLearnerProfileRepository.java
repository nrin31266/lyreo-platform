package com.lyreo.learner.infrastructure.persistence;

import com.lyreo.learner.application.port.LearnerProfileRepository;
import com.lyreo.learner.domain.LearnerDirectory;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcLearnerProfileRepository implements LearnerProfileRepository {
    private static final String PROFILE_COLUMNS = """
        learner_id, display_name, current_level, goal, daily_minutes, focus_area,
        preferences_json, onboarding_completed_at, avatar_preset, avatar_upload_id
        """;
    private static final String DIRECTORY_COLUMNS = """
        SELECT p.learner_id, p.display_name, u.email_snapshot, p.first_seen_at,
               p.onboarding_completed_at, p.goal, p.current_level, p.daily_minutes, p.focus_area,
               p.avatar_preset, p.avatar_upload_id
        """;
    private static final String DIRECTORY_FROM = """
        FROM learner_profile p JOIN identity_user_directory u ON u.id = p.learner_id
        WHERE p.first_seen_at IS NOT NULL
        """;
    private static final String DIRECTORY_FILTERS = """
        AND (:query = ''
             OR strpos(lower(coalesce(p.display_name, '')), lower(:query)) > 0
             OR strpos(lower(coalesce(u.email_snapshot, '')), lower(:query)) > 0)
        AND (CAST(:completed AS boolean) IS NULL
             OR (p.onboarding_completed_at IS NOT NULL) = CAST(:completed AS boolean))
        """;
    private static final String APPEARANCE_UPDATE = """
        display_name = CASE WHEN :changeName THEN :name ELSE display_name END,
        avatar_preset = CASE WHEN :changeAvatar THEN :preset ELSE avatar_preset END,
        avatar_upload_id = CASE WHEN :changeAvatar THEN NULL ELSE avatar_upload_id END,
        avatar_png = CASE WHEN :changeAvatar THEN NULL ELSE avatar_png END,
        updated_at = now()
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLearnerProfileRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<LearnerProfile> findByLearnerId(UUID learnerId) {
        return jdbc.query("SELECT " + PROFILE_COLUMNS + " FROM learner_profile WHERE learner_id = :id",
            Map.of("id", learnerId), this::readProfile).stream().findFirst();
    }

    @Override
    public void recordFirstSeen(UUID learnerId) {
        jdbc.update("""
            INSERT INTO learner_profile (learner_id, first_seen_at)
            SELECT :id, now()
            WHERE NOT EXISTS (
                SELECT 1 FROM learner_profile WHERE learner_id = :id AND first_seen_at IS NOT NULL
            )
            ON CONFLICT (learner_id) DO UPDATE SET first_seen_at = excluded.first_seen_at
            WHERE learner_profile.first_seen_at IS NULL
            """, Map.of("id", learnerId));
    }

    @Override
    public LearnerProfile completeOnboarding(
        UUID learnerId, LearnerProfile.Goal goal, LearnerProfile.Level level, int minutes
    ) {
        var completed = jdbc.query("""
            UPDATE learner_profile
            SET goal = :goal, current_level = :level, daily_minutes = :minutes,
                onboarding_completed_at = now(), updated_at = now()
            WHERE learner_id = :id AND onboarding_completed_at IS NULL
            RETURNING
            """ + PROFILE_COLUMNS, Map.of("id", learnerId, "goal", goal.name(), "level", level.name(), "minutes", minutes),
            this::readProfile);
        return completed.stream().findFirst().orElseGet(() -> findByLearnerId(learnerId).orElseThrow());
    }

    @Override
    public Optional<LearnerProfile> patchProfile(UUID learnerId, LearnerProfile.Patch patch) {
        var params = appearanceParams(learnerId, patch.appearance())
            .addValue("level", patch.currentLevel() == null ? null : patch.currentLevel().name())
            .addValue("goal", patch.goal() == null ? null : patch.goal().name())
            .addValue("minutes", patch.dailyMinutes())
            .addValue("changeFocus", patch.changeFocusArea())
            .addValue("focus", patch.focusArea() == null ? null : patch.focusArea().name());
        return jdbc.query("""
            UPDATE learner_profile SET
                current_level = coalesce(:level, current_level),
                goal = coalesce(:goal, goal),
                daily_minutes = coalesce(:minutes, daily_minutes),
                focus_area = CASE WHEN :changeFocus THEN :focus ELSE focus_area END,
            """ + APPEARANCE_UPDATE + """
            WHERE learner_id = :id AND onboarding_completed_at IS NOT NULL
            RETURNING
            """ + PROFILE_COLUMNS, params, this::readProfile).stream().findFirst();
    }

    @Override
    public boolean patchAppearance(UUID learnerId, LearnerProfile.Appearance appearance) {
        return jdbc.update("UPDATE learner_profile SET " + APPEARANCE_UPDATE
            + " WHERE learner_id = :id AND first_seen_at IS NOT NULL", appearanceParams(learnerId, appearance)) == 1;
    }

    private static MapSqlParameterSource appearanceParams(UUID learnerId, LearnerProfile.Appearance appearance) {
        return new MapSqlParameterSource("id", learnerId)
            .addValue("changeName", appearance.changeName())
            .addValue("name", appearance.name())
            .addValue("changeAvatar", appearance.changeAvatar())
            .addValue("preset", appearance.avatarPreset());
    }

    @Override
    public boolean uploadAvatar(UUID learnerId, byte[] png, boolean completedOnly) {
        return jdbc.update("""
            UPDATE learner_profile SET avatar_preset = NULL, avatar_upload_id = :uploadId,
                avatar_png = :png, updated_at = now()
            WHERE learner_id = :id AND first_seen_at IS NOT NULL
              AND (NOT :completedOnly OR onboarding_completed_at IS NOT NULL)
            """, Map.of("id", learnerId, "uploadId", UUID.randomUUID(), "png", png, "completedOnly", completedOnly)) == 1;
    }

    @Override
    public Optional<byte[]> avatar(UUID learnerId) {
        return jdbc.query("""
            SELECT avatar_png FROM learner_profile
            WHERE learner_id = :id AND first_seen_at IS NOT NULL AND avatar_png IS NOT NULL
            """, Map.of("id", learnerId), (rs, row) -> rs.getBytes(1)).stream().findFirst();
    }

    @Override
    public LearnerPreferences savePreferences(UUID learnerId, LearnerPreferences preferences) {
        jdbc.update("""
            UPDATE learner_profile SET preferences_json = CAST(:preferences AS jsonb), updated_at = now()
            WHERE learner_id = :id
            """, Map.of("id", learnerId, "preferences", mapper.writeValueAsString(preferences)));
        return preferences;
    }

    @Override
    public LearnerDirectory directory(String query, LearnerDirectory.Status status, int page, int size) {
        var params = new MapSqlParameterSource("query", query)
            .addValue("completed", status == null ? null : status == LearnerDirectory.Status.COMPLETED)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*) " + DIRECTORY_FROM + DIRECTORY_FILTERS, params, Long.class);
        var entries = jdbc.query(DIRECTORY_COLUMNS + DIRECTORY_FROM + DIRECTORY_FILTERS + """
            ORDER BY p.first_seen_at DESC, p.learner_id DESC
            LIMIT :limit OFFSET :offset
            """, params, this::readDirectoryEntry);
        return new LearnerDirectory(entries, total, page, size);
    }

    @Override
    public Optional<LearnerDirectory.Entry> directoryEntry(UUID learnerId) {
        return jdbc.query(DIRECTORY_COLUMNS + DIRECTORY_FROM + " AND p.learner_id = :id",
            Map.of("id", learnerId), this::readDirectoryEntry).stream().findFirst();
    }

    private LearnerProfile readProfile(ResultSet rs, int row) throws SQLException {
        return new LearnerProfile(
            rs.getObject("learner_id", UUID.class),
            rs.getString("display_name"),
            rs.getString("current_level"),
            rs.getString("goal"),
            rs.getObject("daily_minutes", Integer.class),
            rs.getString("focus_area"),
            readPreferences(rs.getString("preferences_json")),
            instant(rs, "onboarding_completed_at"),
            rs.getString("avatar_preset"),
            rs.getObject("avatar_upload_id", UUID.class)
        );
    }

    private LearnerDirectory.Entry readDirectoryEntry(ResultSet rs, int row) throws SQLException {
        Instant completed = instant(rs, "onboarding_completed_at");
        return new LearnerDirectory.Entry(
            rs.getObject("learner_id", UUID.class),
            rs.getString("display_name"),
            rs.getString("email_snapshot"),
            instant(rs, "first_seen_at"),
            completed == null ? LearnerDirectory.Status.PENDING : LearnerDirectory.Status.COMPLETED,
            completed,
            rs.getString("goal"),
            rs.getString("current_level"),
            rs.getObject("daily_minutes", Integer.class),
            rs.getString("focus_area"),
            rs.getString("avatar_preset"),
            rs.getObject("avatar_upload_id", UUID.class)
        );
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private LearnerPreferences readPreferences(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return LearnerPreferences.defaults();
        }

        try {
            return mapper.readValue(json, LearnerPreferences.class);
        } catch (tools.jackson.core.JacksonException ignored) {
            // Older preference documents should not prevent access to the learner profile.
            return LearnerPreferences.defaults();
        }
    }
}
