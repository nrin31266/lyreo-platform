package com.lyreo.contracts.grammar;

import java.time.Instant;
import java.util.UUID;

public record GrammarSessionCompletedEvent(
    UUID sessionId,
    UUID learnerId,
    UUID catalogId,
    String catalogKind,
    int correctCount,
    int totalCount,
    Instant occurredAt
) {}
