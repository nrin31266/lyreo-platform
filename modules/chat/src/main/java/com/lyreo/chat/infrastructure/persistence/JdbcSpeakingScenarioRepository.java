package com.lyreo.chat.infrastructure.persistence;

import com.lyreo.chat.application.port.SpeakingScenarioRepository;
import com.lyreo.chat.domain.SpeakingScenario;
import com.lyreo.chat.domain.SpeakingSessionDetail;
import com.lyreo.chat.domain.SpeakingTurn;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public class JdbcSpeakingScenarioRepository implements SpeakingScenarioRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSpeakingScenarioRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public List<SpeakingScenario> listAllScenarios() {
        return jdbc.query(
            """
            SELECT id, code, title, title_vi, category, cefr_level, cover_asset_id,
                   ai_role_name, ai_persona, learner_role, setting_description,
                   opening_line, system_prompt, objectives_json, tts_voice,
                   target_turns, estimated_minutes, access_mode, required_feature_key,
                   status, created_at, updated_at
            FROM speaking_scenario
            ORDER BY created_at DESC, code ASC
            """,
            (rs, rowNum) -> mapScenario(rs)
        );
    }

    @Override
    public List<SpeakingScenario> listPublishedScenarios() {
        return jdbc.query(
            """
            SELECT id, code, title, title_vi, category, cefr_level, cover_asset_id,
                   ai_role_name, ai_persona, learner_role, setting_description,
                   opening_line, system_prompt, objectives_json, tts_voice,
                   target_turns, estimated_minutes, access_mode, required_feature_key,
                   status, created_at, updated_at
            FROM speaking_scenario
            WHERE status = 'PUBLISHED'
            ORDER BY cefr_level ASC, code ASC
            """,
            (rs, rowNum) -> mapScenario(rs)
        );
    }

    @Override
    public Optional<SpeakingScenario> findScenarioById(UUID id) {
        return jdbc.query(
            """
            SELECT id, code, title, title_vi, category, cefr_level, cover_asset_id,
                   ai_role_name, ai_persona, learner_role, setting_description,
                   opening_line, system_prompt, objectives_json, tts_voice,
                   target_turns, estimated_minutes, access_mode, required_feature_key,
                   status, created_at, updated_at
            FROM speaking_scenario
            WHERE id = :id
            """,
            Map.of("id", id),
            (rs, rowNum) -> mapScenario(rs)
        ).stream().findFirst();
    }

    @Override
    public boolean existsByCode(String code, UUID excludeId) {
        Integer count = jdbc.queryForObject(
            """
            SELECT count(*)
            FROM speaking_scenario
            WHERE code = :code
              AND (CAST(:excludeId AS uuid) IS NULL OR id <> CAST(:excludeId AS uuid))
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("excludeId", excludeId, Types.OTHER),
            Integer.class
        );
        return count != null && count > 0;
    }

    @Override
    public SpeakingScenario insertScenario(SpeakingScenario s) {
        return jdbc.queryForObject(
            """
            INSERT INTO speaking_scenario(
                id, code, title, title_vi, category, cefr_level, cover_asset_id,
                ai_role_name, ai_persona, learner_role, setting_description,
                opening_line, system_prompt, objectives_json, tts_voice,
                target_turns, estimated_minutes, access_mode, required_feature_key,
                status, created_at, updated_at
            ) VALUES (
                :id, :code, :title, :titleVi, :category, :cefrLevel, CAST(:coverAssetId AS uuid),
                :aiRoleName, :aiPersona, :learnerRole, :settingDescription,
                :openingLine, :systemPrompt, CAST(:objectivesJson AS jsonb), :ttsVoice,
                :targetTurns, :estimatedMinutes, :accessMode, CAST(:requiredFeatureKey AS varchar),
                :status, now(), now()
            )
            RETURNING id, code, title, title_vi, category, cefr_level, cover_asset_id,
                      ai_role_name, ai_persona, learner_role, setting_description,
                      opening_line, system_prompt, objectives_json, tts_voice,
                      target_turns, estimated_minutes, access_mode, required_feature_key,
                      status, created_at, updated_at
            """,
            scenarioParams(s),
            (rs, rowNum) -> mapScenario(rs)
        );
    }

    @Override
    public Optional<SpeakingScenario> updateScenario(SpeakingScenario s) {
        return jdbc.query(
            """
            UPDATE speaking_scenario
            SET code = :code,
                title = :title,
                title_vi = :titleVi,
                category = :category,
                cefr_level = :cefrLevel,
                cover_asset_id = CAST(:coverAssetId AS uuid),
                ai_role_name = :aiRoleName,
                ai_persona = :aiPersona,
                learner_role = :learnerRole,
                setting_description = :settingDescription,
                opening_line = :openingLine,
                system_prompt = :systemPrompt,
                objectives_json = CAST(:objectivesJson AS jsonb),
                tts_voice = :ttsVoice,
                target_turns = :targetTurns,
                estimated_minutes = :estimatedMinutes,
                access_mode = :accessMode,
                required_feature_key = CAST(:requiredFeatureKey AS varchar),
                status = :status,
                updated_at = now()
            WHERE id = :id
            RETURNING id, code, title, title_vi, category, cefr_level, cover_asset_id,
                      ai_role_name, ai_persona, learner_role, setting_description,
                      opening_line, system_prompt, objectives_json, tts_voice,
                      target_turns, estimated_minutes, access_mode, required_feature_key,
                      status, created_at, updated_at
            """,
            scenarioParams(s),
            (rs, rowNum) -> mapScenario(rs)
        ).stream().findFirst();
    }

    @Override
    public boolean deleteScenario(UUID id) {
        return jdbc.update("DELETE FROM speaking_scenario WHERE id = :id", Map.of("id", id)) > 0;
    }

    @Override
    public SpeakingSessionDetail createSession(UUID sessionId, UUID scenarioId, UUID learnerId, String openingLine) {
        jdbc.update(
            """
            INSERT INTO speaking_session(id, scenario_id, learner_id, status, started_at, updated_at)
            VALUES (:id, :scenarioId, :learnerId, 'ACTIVE', now(), now())
            """,
            Map.of("id", sessionId, "scenarioId", scenarioId, "learnerId", learnerId)
        );
        if (openingLine != null && !openingLine.isBlank()) {
            appendTurn(sessionId, "ASSISTANT", openingLine, null, null);
        }
        return findOwnedSession(sessionId, learnerId).orElseThrow();
    }

    @Override
    public Optional<SpeakingSessionDetail> findOwnedSession(UUID sessionId, UUID learnerId) {
        var rows = jdbc.query(
            """
            SELECT ss.id, ss.scenario_id, sc.title AS scenario_title, ss.learner_id,
                   ss.status, ss.completed_objectives_json, ss.feedback_json,
                   ss.started_at, ss.completed_at
            FROM speaking_session ss
            JOIN speaking_scenario sc ON sc.id = ss.scenario_id
            WHERE ss.id = :sessionId AND ss.learner_id = :learnerId
            """,
            Map.of("sessionId", sessionId, "learnerId", learnerId),
            (rs, rowNum) -> new SpeakingSessionDetail(
                rs.getObject("id", UUID.class),
                rs.getObject("scenario_id", UUID.class),
                rs.getString("scenario_title"),
                rs.getObject("learner_id", UUID.class),
                rs.getString("status"),
                readStringList(rs.getString("completed_objectives_json")),
                readMap(rs.getString("feedback_json")),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                listTurns(sessionId)
            )
        );
        return rows.stream().findFirst();
    }

    @Override
    public SpeakingTurn appendTurn(
        UUID sessionId,
        String role,
        String textContent,
        UUID audioAssetId,
        UUID aiInvocationId
    ) {
        UUID turnId = UUID.randomUUID();
        SpeakingTurn turn = jdbc.queryForObject(
            """
            INSERT INTO speaking_turn(id, session_id, role, text_content, audio_asset_id, ai_invocation_id, created_at)
            VALUES (:id, :sessionId, :role, :textContent, CAST(:audioAssetId AS uuid), CAST(:aiInvocationId AS uuid), now())
            RETURNING id, session_id, role, text_content, audio_asset_id, ai_invocation_id, created_at
            """,
            new MapSqlParameterSource()
                .addValue("id", turnId)
                .addValue("sessionId", sessionId)
                .addValue("role", role)
                .addValue("textContent", textContent)
                .addValue("audioAssetId", audioAssetId, Types.OTHER)
                .addValue("aiInvocationId", aiInvocationId, Types.OTHER),
            (rs, rowNum) -> mapTurn(rs)
        );
        jdbc.update("UPDATE speaking_session SET updated_at = now() WHERE id = :id", Map.of("id", sessionId));
        return turn;
    }

    @Override
    public Optional<SpeakingSessionDetail> completeSession(
        UUID sessionId,
        UUID learnerId,
        List<String> completedObjectives,
        Map<String, Object> feedback
    ) {
        int updated = jdbc.update(
            """
            UPDATE speaking_session
            SET status = 'COMPLETED',
                completed_objectives_json = CAST(:objectivesJson AS jsonb),
                feedback_json = CAST(:feedbackJson AS jsonb),
                completed_at = COALESCE(completed_at, now()),
                updated_at = now()
            WHERE id = :sessionId AND learner_id = :learnerId
            """,
            new MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("learnerId", learnerId)
                .addValue("objectivesJson", writeJson(completedObjectives == null ? List.of() : completedObjectives))
                .addValue("feedbackJson", writeJson(feedback == null ? Map.of() : feedback))
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return findOwnedSession(sessionId, learnerId);
    }

    private List<SpeakingTurn> listTurns(UUID sessionId) {
        return jdbc.query(
            """
            SELECT id, session_id, role, text_content, audio_asset_id, ai_invocation_id, created_at
            FROM speaking_turn
            WHERE session_id = :sessionId
            ORDER BY created_at ASC, id ASC
            """,
            Map.of("sessionId", sessionId),
            (rs, rowNum) -> mapTurn(rs)
        );
    }

    private MapSqlParameterSource scenarioParams(SpeakingScenario s) {
        return new MapSqlParameterSource()
            .addValue("id", s.id())
            .addValue("code", s.code())
            .addValue("title", s.title())
            .addValue("titleVi", s.titleVi())
            .addValue("category", s.category())
            .addValue("cefrLevel", s.cefrLevel())
            .addValue("coverAssetId", s.coverAssetId(), Types.OTHER)
            .addValue("aiRoleName", s.aiRoleName())
            .addValue("aiPersona", s.aiPersona())
            .addValue("learnerRole", s.learnerRole())
            .addValue("settingDescription", s.settingDescription())
            .addValue("openingLine", s.openingLine())
            .addValue("systemPrompt", s.systemPrompt())
            .addValue("objectivesJson", writeJson(s.objectives() == null ? List.of() : s.objectives()))
            .addValue("ttsVoice", s.ttsVoice())
            .addValue("targetTurns", s.targetTurns())
            .addValue("estimatedMinutes", s.estimatedMinutes())
            .addValue("accessMode", s.accessMode())
            .addValue("requiredFeatureKey", s.requiredFeatureKey(), Types.VARCHAR)
            .addValue("status", s.status());
    }

    private SpeakingScenario mapScenario(ResultSet rs) throws SQLException {
        return new SpeakingScenario(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("title_vi"),
            rs.getString("category"),
            rs.getString("cefr_level"),
            rs.getObject("cover_asset_id", UUID.class),
            rs.getString("ai_role_name"),
            rs.getString("ai_persona"),
            rs.getString("learner_role"),
            rs.getString("setting_description"),
            rs.getString("opening_line"),
            rs.getString("system_prompt"),
            readStringList(rs.getString("objectives_json")),
            rs.getString("tts_voice"),
            rs.getInt("target_turns"),
            rs.getInt("estimated_minutes"),
            rs.getString("access_mode"),
            rs.getString("required_feature_key"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static SpeakingTurn mapTurn(ResultSet rs) throws SQLException {
        return new SpeakingTurn(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getString("role"),
            rs.getString("text_content"),
            rs.getObject("audio_asset_id", UUID.class),
            rs.getObject("ai_invocation_id", UUID.class),
            toInstant(rs.getTimestamp("created_at"))
        );
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = mapper.readValue(json, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = mapper.readValue(json, MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
