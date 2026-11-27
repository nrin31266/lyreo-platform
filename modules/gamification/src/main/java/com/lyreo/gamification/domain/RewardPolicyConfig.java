package com.lyreo.gamification.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RewardPolicyConfig(
    String ruleVersion,
    boolean directLessonActivityDiamondsEnabled,
    Instant cutoverEffectiveAt,
    int xpPerLevel,
    Integer xpGrowthPerLevel,
    Integer maxLevel,
    Double xpLevelMultiplier,
    Integer milestoneInterval,
    Integer milestoneDiamondBase,
    Integer milestoneDiamondGrowth
) {
    public static final String DEFAULT_RULE_VERSION = "v1";
    public static final Instant DEFAULT_CUTOVER = Instant.parse("2026-11-25T00:00:00Z");
    public static final int DEFAULT_XP_PER_LEVEL = LevelProgression.DEFAULT_BASE_XP;
    public static final int DEFAULT_XP_GROWTH_PER_LEVEL = LevelProgression.DEFAULT_XP_GROWTH;
    public static final int DEFAULT_MAX_LEVEL = LevelProgression.HARD_MAX_LEVEL;
    public static final double DEFAULT_XP_LEVEL_MULTIPLIER = LevelProgression.DEFAULT_XP_MULTIPLIER;
    public static final int DEFAULT_MILESTONE_INTERVAL = LevelProgression.DEFAULT_MILESTONE_INTERVAL;
    public static final int DEFAULT_MILESTONE_DIAMOND_BASE = LevelProgression.DEFAULT_MILESTONE_DIAMOND_BASE;
    public static final int DEFAULT_MILESTONE_DIAMOND_GROWTH = LevelProgression.DEFAULT_MILESTONE_DIAMOND_GROWTH;

    public RewardPolicyConfig {
        if (xpGrowthPerLevel == null) {
            xpGrowthPerLevel = DEFAULT_XP_GROWTH_PER_LEVEL;
        }
        if (maxLevel == null) {
            maxLevel = DEFAULT_MAX_LEVEL;
        }
        if (xpLevelMultiplier == null) {
            xpLevelMultiplier = DEFAULT_XP_LEVEL_MULTIPLIER;
        }
        if (milestoneInterval == null) {
            milestoneInterval = DEFAULT_MILESTONE_INTERVAL;
        }
        if (milestoneDiamondBase == null) {
            milestoneDiamondBase = DEFAULT_MILESTONE_DIAMOND_BASE;
        }
        if (milestoneDiamondGrowth == null) {
            milestoneDiamondGrowth = DEFAULT_MILESTONE_DIAMOND_GROWTH;
        }
    }

    public RewardPolicyConfig(
        String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        int xpPerLevel
    ) {
        this(
            ruleVersion,
            directLessonActivityDiamondsEnabled,
            cutoverEffectiveAt,
            xpPerLevel,
            DEFAULT_XP_GROWTH_PER_LEVEL,
            DEFAULT_MAX_LEVEL,
            DEFAULT_XP_LEVEL_MULTIPLIER,
            DEFAULT_MILESTONE_INTERVAL,
            DEFAULT_MILESTONE_DIAMOND_BASE,
            DEFAULT_MILESTONE_DIAMOND_GROWTH
        );
    }

    public RewardPolicyConfig(
        String ruleVersion,
        boolean directLessonActivityDiamondsEnabled,
        Instant cutoverEffectiveAt,
        int xpPerLevel,
        Integer xpGrowthPerLevel,
        Integer maxLevel
    ) {
        this(
            ruleVersion,
            directLessonActivityDiamondsEnabled,
            cutoverEffectiveAt,
            xpPerLevel,
            xpGrowthPerLevel,
            maxLevel,
            DEFAULT_XP_LEVEL_MULTIPLIER,
            DEFAULT_MILESTONE_INTERVAL,
            DEFAULT_MILESTONE_DIAMOND_BASE,
            DEFAULT_MILESTONE_DIAMOND_GROWTH
        );
    }

    public static RewardPolicyConfig defaults() {
        return new RewardPolicyConfig(
            DEFAULT_RULE_VERSION,
            false,
            DEFAULT_CUTOVER,
            DEFAULT_XP_PER_LEVEL,
            DEFAULT_XP_GROWTH_PER_LEVEL,
            DEFAULT_MAX_LEVEL,
            DEFAULT_XP_LEVEL_MULTIPLIER,
            DEFAULT_MILESTONE_INTERVAL,
            DEFAULT_MILESTONE_DIAMOND_BASE,
            DEFAULT_MILESTONE_DIAMOND_GROWTH
        );
    }

    public int normalizedXpPerLevel() {
        return xpPerLevel > 0 ? xpPerLevel : DEFAULT_XP_PER_LEVEL;
    }

    public int normalizedXpGrowthPerLevel() {
        return xpGrowthPerLevel != null && xpGrowthPerLevel >= 0
            ? xpGrowthPerLevel
            : DEFAULT_XP_GROWTH_PER_LEVEL;
    }

    public int normalizedMaxLevel() {
        return maxLevel != null && maxLevel >= 1 && maxLevel <= LevelProgression.HARD_MAX_LEVEL
            ? maxLevel
            : DEFAULT_MAX_LEVEL;
    }

    public double normalizedXpLevelMultiplier() {
        if (xpLevelMultiplier == null || !Double.isFinite(xpLevelMultiplier) || xpLevelMultiplier < 1.0) {
            return DEFAULT_XP_LEVEL_MULTIPLIER;
        }
        return Math.min(LevelProgression.MAX_XP_MULTIPLIER, xpLevelMultiplier);
    }

    public int normalizedMilestoneInterval() {
        return milestoneInterval != null && milestoneInterval >= 0
            ? Math.min(50, milestoneInterval)
            : DEFAULT_MILESTONE_INTERVAL;
    }

    public int normalizedMilestoneDiamondBase() {
        return milestoneDiamondBase != null && milestoneDiamondBase >= 0
            ? milestoneDiamondBase
            : DEFAULT_MILESTONE_DIAMOND_BASE;
    }

    public int normalizedMilestoneDiamondGrowth() {
        return milestoneDiamondGrowth != null && milestoneDiamondGrowth >= 0
            ? milestoneDiamondGrowth
            : DEFAULT_MILESTONE_DIAMOND_GROWTH;
    }

    public String normalizedRuleVersion() {
        return (ruleVersion == null || ruleVersion.isBlank()) ? DEFAULT_RULE_VERSION : ruleVersion.trim();
    }
}
