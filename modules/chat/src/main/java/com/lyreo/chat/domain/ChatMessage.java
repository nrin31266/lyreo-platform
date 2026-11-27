package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(
    UUID id,
    UUID conversationId,
    String role,
    String content,
    UUID aiInvocationId,
    Instant createdAt
) {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";
}
