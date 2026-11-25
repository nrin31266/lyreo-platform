package com.lyreo.gamification.domain;

import java.util.UUID;

public record AdminMissionDefinition(
    UUID id,
    String code,
    String title,
    MissionDefinition.Metric metric,
    int target,
    int xpReward,
    int diamondReward,
    MissionDefinition.Recurrence recurrence,
    String ruleVersion,
    boolean enabled
) {}
