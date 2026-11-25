package com.lyreo.chat.application;

import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.chat.application.port.ChatRepository;
import com.lyreo.chat.domain.AdminChatSummary;
import com.lyreo.chat.domain.ChatConversation;
import com.lyreo.chat.domain.ChatConversationDetail;
import com.lyreo.chat.domain.ChatMessage;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.api.QuotaDecision;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class EnglishTutorService {
    public static final FeatureKey FEATURE_CHAT_TUTOR = FeatureKey.of("chat.tutor");

    private final AiInvocationService ai;
    private final EnglishTutorPrompt prompts;
    private final EntitlementService entitlements;
    private final ChatRepository chatRepository;

    public EnglishTutorService(AiInvocationService ai, EnglishTutorPrompt prompts) {
        this(ai, prompts, null, null);
    }

    public EnglishTutorService(
        AiInvocationService ai,
        EnglishTutorPrompt prompts,
        EntitlementService entitlements,
        ChatRepository chatRepository
    ) {
        this.ai = Objects.requireNonNull(ai, "ai");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.entitlements = entitlements;
        this.chatRepository = chatRepository;
    }

    public String ask(String learnerLevel, String question) {
        AiExecutionResult result = ai.execute(
            AiCapability.GENERAL_LLM,
            prompts.systemPrompt(learnerLevel),
            Map.of("question", question),
            Map.of("response_format", "text")
        );
        return extractAnswer(result);
    }

    public AskResult ask(
        UUID learnerId,
        UUID conversationId,
        String learnerLevel,
        String question,
        String title,
        String idempotencyKey
    ) {
        Objects.requireNonNull(learnerId, "learnerId");
        if (question == null || question.isBlank()) {
            throw new RequestValidationException("question is required");
        }

        List<ChatMessage> history = List.of();
        if (conversationId != null && chatRepository != null) {
            chatRepository.findOwnedConversation(conversationId, learnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
            history = chatRepository.listMessages(conversationId);
        }

        String operationId = (idempotencyKey != null && !idempotencyKey.isBlank())
            ? idempotencyKey.trim()
            : UUID.randomUUID().toString();
        QuotaDecision decision = entitlements != null
            ? entitlements.consumeQuota(learnerId, FEATURE_CHAT_TUTOR, operationId)
            : new QuotaDecision(FEATURE_CHAT_TUTOR.value(), operationId, true, 0, 0, null, "dev-v1");

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("question", question.trim());
        if (!history.isEmpty()) {
            List<Map<String, String>> recent = history.stream()
                .skip(Math.max(0, history.size() - 6))
                .map(msg -> Map.of("role", msg.role(), "content", msg.content()))
                .toList();
            input.put("history", recent);
        }

        AiExecutionResult result = ai.execute(
            AiCapability.GENERAL_LLM,
            prompts.systemPrompt(learnerLevel),
            input,
            Map.of("response_format", "text")
        );
        String answer = extractAnswer(result);

        if (chatRepository == null) {
            return new AskResult(
                conversationId,
                null,
                answer,
                result.invocationId(),
                decision.remaining(),
                decision.resetAt()
            );
        }

        String resolvedTitle = resolveTitle(title, question);
        ChatRepository.ChatTurnPersistenceResult saved = chatRepository.persistTurn(
            conversationId,
            learnerId,
            resolvedTitle,
            question.trim(),
            answer,
            result.invocationId()
        );
        return new AskResult(
            saved.conversationId(),
            saved.assistantMessageId(),
            answer,
            result.invocationId(),
            decision.remaining(),
            decision.resetAt()
        );
    }

    public List<ChatConversation> listConversations(UUID learnerId, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("Invalid conversation page or size");
        }
        if (chatRepository == null) {
            return List.of();
        }
        return chatRepository.listOwnedConversations(learnerId, page, size);
    }

    public ChatConversationDetail getConversation(UUID learnerId, UUID conversationId) {
        if (chatRepository == null) {
            throw new ResourceNotFoundException("Conversation not found");
        }
        ChatConversation conversation = chatRepository.findOwnedConversation(conversationId, learnerId)
            .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        List<ChatMessage> messages = chatRepository.listMessages(conversation.id());
        return new ChatConversationDetail(conversation, messages);
    }

    public AdminChatSummary adminSummary() {
        if (chatRepository == null) {
            return new AdminChatSummary(0L, 0L, 0L, 0L, List.of());
        }
        Instant todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant weekStart = todayStart.minusSeconds(6L * 86_400L);
        return chatRepository.summarizePlatform(todayStart, weekStart, 20);
    }

    private static String extractAnswer(AiExecutionResult result) {
        Object text = result.output().get("content");
        if (text == null) {
            text = result.output().get("text");
        }
        return text == null ? "" : text.toString();
    }

    private static String resolveTitle(String title, String question) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String trimmed = question.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    public record AskResult(
        UUID conversationId,
        UUID messageId,
        String answer,
        UUID aiInvocationId,
        int remainingQuota,
        Instant quotaResetAt
    ) {}
}
