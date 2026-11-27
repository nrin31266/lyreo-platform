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
    String ruleVersion,
    UUID badgeAssetId,
    String badgeTier,
    String category,
    String badgeUrl
) {
    public AchievementView(
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
    ) {
        this(
            achievementId, code, title, description, metric, target, progress, unlocked, unlockedAt,
            xpReward, diamondReward, ruleVersion, null, "BRONZE", "MILESTONE", null
        );
    }
}
