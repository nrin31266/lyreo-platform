package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SpeakingSessionDetail(
    UUID id,
    UUID scenarioId,
    String scenarioTitle,
    UUID learnerId,
    String status,
    List<String> completedObjectives,
    Map<String, Object> feedback,
    Instant startedAt,
    Instant completedAt,
    List<SpeakingTurn> turns
) {}
