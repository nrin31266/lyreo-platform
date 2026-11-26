package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record SpeakingTurn(
    UUID id,
    UUID sessionId,
    String role,
    String textContent,
    UUID audioAssetId,
    UUID aiInvocationId,
    Instant createdAt
) {}
