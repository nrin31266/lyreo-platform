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
    boolean enabled,
    UUID badgeAssetId,
    String badgeTier,
    String category
) {
    public AchievementDefinition(
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
    ) {
        this(id, code, title, description, metric, target, xpReward, diamondReward, ruleVersion, enabled,
            null, "BRONZE", "MILESTONE");
    }
}
