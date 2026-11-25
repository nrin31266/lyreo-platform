package com.lyreo.gamification.domain;

import java.time.Instant;
import java.util.UUID;

public record AchievementView(
    UUID achievementId,
    String code,
    String title,
    String description,
    String metric,
    int target,
    int progress,
    boolean unlocked,
    Instant unlockedAt,
    int xpReward,
    int diamondReward,
    String ruleVersion
) {}
