package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SpeakingScenario(
    UUID id,
    String code,
    String title,
    String titleVi,
    String category,
    String cefrLevel,
    UUID coverAssetId,
    String aiRoleName,
    String aiPersona,
    String learnerRole,
    String settingDescription,
    String openingLine,
    String systemPrompt,
    List<String> objectives,
    String ttsVoice,
    int targetTurns,
    int estimatedMinutes,
    String accessMode,
    String requiredFeatureKey,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}
