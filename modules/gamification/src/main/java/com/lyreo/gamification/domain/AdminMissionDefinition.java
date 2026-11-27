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
    boolean enabled,
    String category,
    String difficulty
) {
    public AdminMissionDefinition(
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
    ) {
        this(id, code, title, metric, target, xpReward, diamondReward, recurrence, ruleVersion, enabled, "GENERAL", "NORMAL");
    }
}
