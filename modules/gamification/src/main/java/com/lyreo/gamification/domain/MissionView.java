package com.lyreo.gamification.domain;

import java.time.Instant;
import java.util.UUID;

public record MissionView(
    UUID missionId,
    String code,
    String title,
    String metric,
    String recurrence,
    int target,
    int progress,
    boolean completed,
    Instant completedAt,
    boolean claimed,
    Instant claimedAt,
    int xpReward,
    int diamondReward,
    String periodKey,
    Instant periodEndsAt,
    String ruleVersion
) {}
