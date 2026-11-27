package com.lyreo.chat.infrastructure.persistence;

import com.lyreo.chat.application.port.ChatRepository;
import com.lyreo.chat.domain.AdminChatSummary;
import com.lyreo.chat.domain.ChatConversation;
import com.lyreo.chat.domain.ChatMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JdbcChatRepository implements ChatRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcChatRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChatConversation> findOwnedConversation(UUID conversationId, UUID learnerId) {
        return jdbc.query(
            """
            SELECT c.id, c.learner_id, c.title, c.created_at, c.updated_at,
                   (SELECT LEFT(m.content, 160)
                    FROM chat_message m
                    WHERE m.conversation_id = c.id
                    ORDER BY m.created_at DESC, m.id DESC
                    LIMIT 1) AS last_message_preview,
                   (SELECT count(*)::int
                    FROM chat_message m
                    WHERE m.conversation_id = c.id) AS message_count
            FROM chat_conversation c
            WHERE c.id = :id AND c.learner_id = :learnerId
            """,
            Map.of("id", conversationId, "learnerId", learnerId),
            (rs, rowNum) -> mapConversation(rs)
        ).stream().findFirst();
    }

    @Override
    public List<ChatConversation> listOwnedConversations(UUID learnerId, int page, int size) {
        return jdbc.query(
            """
            SELECT c.id, c.learner_id, c.title, c.created_at, c.updated_at,
                   (SELECT LEFT(m.content, 160)
                    FROM chat_message m
                    WHERE m.conversation_id = c.id
                    ORDER BY m.created_at DESC, m.id DESC
                    LIMIT 1) AS last_message_preview,
                   (SELECT count(*)::int
                    FROM chat_message m
                    WHERE m.conversation_id = c.id) AS message_count
            FROM chat_conversation c
            WHERE c.learner_id = :learnerId
            ORDER BY COALESCE(c.updated_at, c.created_at) DESC, c.id DESC
            LIMIT :limit OFFSET :offset
            """,
            Map.of(
                "learnerId", learnerId,
                "limit", size,
                "offset", (long) page * size
            ),
            (rs, rowNum) -> mapConversation(rs)
        );
    }

    @Override
    public long countOwnedConversations(UUID learnerId) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM chat_conversation WHERE learner_id = :learnerId",
            Map.of("learnerId", learnerId),
            Long.class
        );
        return count != null ? count : 0L;
    }

    @Override
    @Transactional
    public Optional<ChatConversation> renameOwnedConversation(UUID conversationId, UUID learnerId, String title) {
        int updated = jdbc.update(
            """
            UPDATE chat_conversation
            SET title = :title, updated_at = now()
            WHERE id = :id AND learner_id = :learnerId
            """,
            Map.of("id", conversationId, "learnerId", learnerId, "title", title)
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return findOwnedConversation(conversationId, learnerId);
    }

    @Override
    @Transactional
    public boolean deleteOwnedConversation(UUID conversationId, UUID learnerId) {
        return jdbc.update(
            "DELETE FROM chat_conversation WHERE id = :id AND learner_id = :learnerId",
            Map.of("id", conversationId, "learnerId", learnerId)
        ) > 0;
    }

    @Override
    public List<ChatMessage> listMessages(UUID conversationId) {
        return jdbc.query(
            """
            SELECT id, conversation_id, role, content, ai_invocation_id, created_at
            FROM chat_message
            WHERE conversation_id = :conversationId
            ORDER BY created_at ASC, CASE role WHEN 'USER' THEN 0 ELSE 1 END ASC, id ASC
            """,
            Map.of("conversationId", conversationId),
            (rs, rowNum) -> new ChatMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getString("role"),
                rs.getString("content"),
                rs.getObject("ai_invocation_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()
            )
        );
    }

    @Override
    @Transactional
    public ChatTurnPersistenceResult persistTurn(
        UUID existingConversationId,
        UUID learnerId,
        String title,
        String userContent,
        String assistantContent,
        UUID aiInvocationId
    ) {
        UUID conversationId = existingConversationId;
        if (conversationId == null) {
            conversationId = UUID.randomUUID();
            jdbc.update(
                """
                INSERT INTO chat_conversation(id, learner_id, title, created_at, updated_at)
                VALUES (:id, :learnerId, :title, now(), now())
                """,
                new MapSqlParameterSource()
                    .addValue("id", conversationId)
                    .addValue("learnerId", learnerId)
                    .addValue("title", title)
            );
        } else {
            jdbc.update(
                """
                UPDATE chat_conversation
                SET updated_at = now()
                WHERE id = :id AND learner_id = :learnerId
                """,
                Map.of("id", conversationId, "learnerId", learnerId)
            );
        }

        UUID userMessageId = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO chat_message(id, conversation_id, role, content, ai_invocation_id, created_at)
            VALUES (:id, :conversationId, 'USER', :content, NULL, clock_timestamp())
            """,
            Map.of(
                "id", userMessageId,
                "conversationId", conversationId,
                "content", userContent
            )
        );

        UUID assistantMessageId = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO chat_message(id, conversation_id, role, content, ai_invocation_id, created_at)
            VALUES (:id, :conversationId, 'ASSISTANT', :content, :aiInvocationId, clock_timestamp() + interval '1 microsecond')
            """,
            new MapSqlParameterSource()
                .addValue("id", assistantMessageId)
                .addValue("conversationId", conversationId)
                .addValue("content", assistantContent)
                .addValue("aiInvocationId", aiInvocationId)
        );

        return new ChatTurnPersistenceResult(conversationId, userMessageId, assistantMessageId);
    }

    @Override
    public AdminChatSummary summarizePlatform(Instant todayStartUtc, Instant weekStartUtc, int limit) {
        int clampedLimit = Math.max(1, Math.min(limit, 50));
        Long totalConversations = jdbc.queryForObject(
            "SELECT count(*) FROM chat_conversation",
            Map.of(),
            Long.class
        );
        Long activeLearners = jdbc.queryForObject(
            "SELECT count(DISTINCT learner_id) FROM chat_conversation",
            Map.of(),
            Long.class
        );
        Long userTurnsToday = jdbc.queryForObject(
            "SELECT count(*) FROM chat_message WHERE role = 'USER' AND created_at >= :todayStart",
            Map.of("todayStart", Timestamp.from(todayStartUtc)),
            Long.class
        );
        Long userTurns7d = jdbc.queryForObject(
            "SELECT count(*) FROM chat_message WHERE role = 'USER' AND created_at >= :weekStart",
            Map.of("weekStart", Timestamp.from(weekStartUtc)),
            Long.class
        );
        List<AdminChatSummary.ConversationTelemetry> recent = jdbc.query(
            """
            SELECT
                c.id,
                c.learner_id,
                COUNT(m.id)::int AS message_count,
                c.created_at,
                c.updated_at
            FROM chat_conversation c
            LEFT JOIN chat_message m ON m.conversation_id = c.id
            GROUP BY c.id, c.learner_id, c.created_at, c.updated_at
            ORDER BY COALESCE(c.updated_at, c.created_at) DESC, c.id DESC
            LIMIT :limit
            """,
            Map.of("limit", clampedLimit),
            (rs, rowNum) -> {
                Timestamp updated = rs.getTimestamp("updated_at");
                return new AdminChatSummary.ConversationTelemetry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("learner_id", UUID.class),
                    rs.getInt("message_count"),
                    rs.getTimestamp("created_at").toInstant(),
                    updated != null ? updated.toInstant() : rs.getTimestamp("created_at").toInstant()
                );
            }
        );
        return new AdminChatSummary(
            totalConversations != null ? totalConversations : 0L,
            activeLearners != null ? activeLearners : 0L,
            userTurnsToday != null ? userTurnsToday : 0L,
            userTurns7d != null ? userTurns7d : 0L,
            recent
        );
    }

    private static ChatConversation mapConversation(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ChatConversation(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            updated == null ? null : updated.toInstant(),
            rs.getString("last_message_preview"),
            rs.getInt("message_count")
        );
    }
}
