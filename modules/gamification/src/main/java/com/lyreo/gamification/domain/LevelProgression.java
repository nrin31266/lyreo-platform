package com.lyreo.gamification.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Exponential-hybrid progressive XP curve utility capped at Lv. 99 with milestone rewards.
 *
 * <p>Each level {@code L >= 1} requires {@code round((baseXp + (L - 1) * xpGrowthPerLevel) * multiplier^(L - 1))}
 * XP to advance to {@code L + 1}. Reaching milestone levels (e.g. every 5 levels + Lv. 99) grants
 * automatic bonus Diamonds. Above {@code maxLevel}, total XP continues accumulating while {@code level} caps.</p>
 */
public final class LevelProgression {
    public static final int HARD_MAX_LEVEL = 99;
    public static final int DEFAULT_BASE_XP = 100;
    public static final int DEFAULT_XP_GROWTH = 15;
    public static final double DEFAULT_XP_MULTIPLIER = 1.02;
    public static final double MAX_XP_MULTIPLIER = 1.25;
    public static final int DEFAULT_MILESTONE_INTERVAL = 5;
    public static final int DEFAULT_MILESTONE_DIAMOND_BASE = 15;
    public static final int DEFAULT_MILESTONE_DIAMOND_GROWTH = 10;
    private static final long MAX_STEP_XP = 1_000_000_000L;

    private LevelProgression() {}

    public static long xpRequiredForStep(int level, int baseXp, int xpGrowthPerLevel) {
        return xpRequiredForStep(level, baseXp, xpGrowthPerLevel, 1.0);
    }

    public static long xpRequiredForStep(int level, int baseXp, int xpGrowthPerLevel, double multiplier) {
        int safeLevel = Math.max(1, level);
        int safeBase = Math.max(1, baseXp);
        int safeGrowth = Math.max(0, xpGrowthPerLevel);
        double safeMult = sanitizeMultiplier(multiplier);
        double linear = safeBase + (double) (safeLevel - 1) * safeGrowth;
        double scaled = linear * Math.pow(safeMult, safeLevel - 1);
        if (!Double.isFinite(scaled) || scaled > MAX_STEP_XP) {
            return MAX_STEP_XP;
        }
        return Math.max(1L, Math.round(scaled));
    }

    public static long totalXpForLevel(int targetLevel, int baseXp, int xpGrowthPerLevel) {
        return totalXpForLevel(targetLevel, baseXp, xpGrowthPerLevel, 1.0);
    }

    public static long totalXpForLevel(int targetLevel, int baseXp, int xpGrowthPerLevel, double multiplier) {
        int clampedTarget = Math.max(1, Math.min(HARD_MAX_LEVEL, targetLevel));
        long sum = 0L;
        for (int lvl = 1; lvl < clampedTarget; lvl++) {
            sum += xpRequiredForStep(lvl, baseXp, xpGrowthPerLevel, multiplier);
        }
        return sum;
    }

    public static Progress evaluate(long totalXp, RewardPolicyConfig config) {
        RewardPolicyConfig effective = config == null ? RewardPolicyConfig.defaults() : config;
        return evaluate(
            totalXp,
            effective.normalizedXpPerLevel(),
            effective.normalizedXpGrowthPerLevel(),
            effective.normalizedXpLevelMultiplier(),
            effective.normalizedMaxLevel()
        );
    }

    public static Progress evaluate(long totalXp, int baseXp, int xpGrowthPerLevel, int maxLevel) {
        return evaluate(totalXp, baseXp, xpGrowthPerLevel, 1.0, maxLevel);
    }

    public static Progress evaluate(
        long totalXp,
        int baseXp,
        int xpGrowthPerLevel,
        double multiplier,
        int maxLevel
    ) {
        long xp = Math.max(0L, totalXp);
        int safeMax = Math.max(1, Math.min(HARD_MAX_LEVEL, maxLevel));

        int level = 1;
        long consumed = 0L;
        while (level < safeMax) {
            long step = xpRequiredForStep(level, baseXp, xpGrowthPerLevel, multiplier);
            if (xp - consumed < step) {
                long intoLevel = xp - consumed;
                return new Progress(
                    level,
                    safeMax,
                    false,
                    intoLevel,
                    Math.max(1L, step - intoLevel),
                    (int) Math.min(Integer.MAX_VALUE, step),
                    consumed,
                    consumed + step
                );
            }
            consumed += step;
            level++;
        }

        return new Progress(safeMax, safeMax, true, 0L, 0L, 0, consumed, consumed);
    }

    public static int milestoneRewardDiamonds(int level, RewardPolicyConfig config) {
        RewardPolicyConfig effective = config == null ? RewardPolicyConfig.defaults() : config;
        return milestoneRewardDiamonds(
            level,
            effective.normalizedMilestoneInterval(),
            effective.normalizedMilestoneDiamondBase(),
            effective.normalizedMilestoneDiamondGrowth(),
            effective.normalizedMaxLevel()
        );
    }

    public static int milestoneRewardDiamonds(
        int level,
        int milestoneInterval,
        int milestoneDiamondBase,
        int milestoneDiamondGrowth,
        int maxLevel
    ) {
        if (level <= 1 || milestoneInterval <= 0 || milestoneDiamondBase <= 0 && milestoneDiamondGrowth <= 0) {
            return 0;
        }
        int safeMax = Math.max(1, Math.min(HARD_MAX_LEVEL, maxLevel));
        if (level > safeMax) {
            return 0;
        }
        boolean isPeriodic = (level % milestoneInterval) == 0;
        boolean isCap = level == safeMax;
        if (!isPeriodic && !isCap) {
            return 0;
        }
        int tierIndex = Math.max(0, (level / milestoneInterval) - (isPeriodic ? 1 : 0));
        return Math.max(0, milestoneDiamondBase) + tierIndex * Math.max(0, milestoneDiamondGrowth);
    }

    public static List<MilestoneAward> crossedMilestones(int previousLevel, int newLevel, RewardPolicyConfig config) {
        RewardPolicyConfig effective = config == null ? RewardPolicyConfig.defaults() : config;
        int safePrev = Math.max(1, previousLevel);
        int safeNew = Math.min(effective.normalizedMaxLevel(), newLevel);
        if (safeNew <= safePrev) {
            return List.of();
        }
        List<MilestoneAward> awards = new ArrayList<>();
        for (int lvl = safePrev + 1; lvl <= safeNew; lvl++) {
            int diamonds = milestoneRewardDiamonds(lvl, effective);
            if (diamonds > 0) {
                awards.add(new MilestoneAward(lvl, diamonds));
            }
        }
        return awards;
    }

    private static double sanitizeMultiplier(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < 1.0) {
            return 1.0;
        }
        return Math.min(MAX_XP_MULTIPLIER, multiplier);
    }

    public record Progress(
        int level,
        int maxLevel,
        boolean maxLevelReached,
        long xpIntoCurrentLevel,
        long xpToNextLevel,
        int xpForCurrentStep,
        long currentLevelStartXp,
        long nextLevelStartXp
    ) {}

    public record MilestoneAward(int level, int diamonds) {}
}
