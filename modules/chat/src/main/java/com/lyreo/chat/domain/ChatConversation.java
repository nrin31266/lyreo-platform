package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatConversation(
    UUID id,
    UUID learnerId,
    String title,
    Instant createdAt,
    Instant updatedAt
) {}
