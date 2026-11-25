package com.lyreo.gamification.domain;

import java.util.UUID;

public record AchievementDefinition(
    UUID id,
    String code,
    String title,
    String description,
    String metric,
    int target,
    int xpReward,
    int diamondReward,
    String ruleVersion,
    boolean enabled
) {}
