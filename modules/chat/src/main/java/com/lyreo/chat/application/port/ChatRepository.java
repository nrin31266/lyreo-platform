package com.lyreo.chat.application.port;

import com.lyreo.chat.domain.AdminChatSummary;
import com.lyreo.chat.domain.ChatConversation;
import com.lyreo.chat.domain.ChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatRepository {
    Optional<ChatConversation> findOwnedConversation(UUID conversationId, UUID learnerId);

    List<ChatConversation> listOwnedConversations(UUID learnerId, int page, int size);

    List<ChatMessage> listMessages(UUID conversationId);

    ChatTurnPersistenceResult persistTurn(
        UUID existingConversationId,
        UUID learnerId,
        String title,
        String userContent,
        String assistantContent,
        UUID aiInvocationId
    );

    default AdminChatSummary summarizePlatform(Instant todayStartUtc, Instant weekStartUtc, int limit) {
        return new AdminChatSummary(0L, 0L, 0L, 0L, List.of());
    }

    record ChatTurnPersistenceResult(
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId
    ) {}
}
