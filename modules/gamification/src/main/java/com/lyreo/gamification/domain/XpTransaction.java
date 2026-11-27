package com.lyreo.gamification.domain;

import java.time.Instant;
import java.util.UUID;

public record XpTransaction(
    UUID id,
    UUID learnerId,
    String sourceType,
    int amount,
    String idempotencyKey,
    String ruleVersion,
    UUID referenceId,
    Instant createdAt
) {}
