package com.lyreo.gamification.domain;

import java.time.LocalDate;
import java.util.UUID;

public record GamificationSummary(
    UUID learnerId,
    int level,
    long xp,
    int xpPerLevel,
    long xpIntoCurrentLevel,
    long xpToNextLevel,
    int diamondBalance,
    int currentStreak,
    int longestStreak,
    LocalDate lastStudyDate,
    String ruleVersion,
    int xpGrowthPerLevel,
    double xpLevelMultiplier,
    int maxLevel,
    boolean maxLevelReached,
    int milestoneInterval,
    int nextMilestoneLevel,
    int nextMilestoneDiamonds
) {
    public GamificationSummary(
        UUID learnerId,
        int level,
        long xp,
        int xpPerLevel,
        long xpIntoCurrentLevel,
        long xpToNextLevel,
        int diamondBalance,
        int currentStreak,
        int longestStreak,
        LocalDate lastStudyDate,
        String ruleVersion
    ) {
        this(
            learnerId,
            level,
            xp,
            xpPerLevel,
            xpIntoCurrentLevel,
            xpToNextLevel,
            diamondBalance,
            currentStreak,
            longestStreak,
            lastStudyDate,
            ruleVersion,
            LevelProgression.DEFAULT_XP_GROWTH,
            LevelProgression.DEFAULT_XP_MULTIPLIER,
            LevelProgression.HARD_MAX_LEVEL,
            false,
            LevelProgression.DEFAULT_MILESTONE_INTERVAL,
            LevelProgression.DEFAULT_MILESTONE_INTERVAL,
            LevelProgression.DEFAULT_MILESTONE_DIAMOND_BASE
        );
    }
}
