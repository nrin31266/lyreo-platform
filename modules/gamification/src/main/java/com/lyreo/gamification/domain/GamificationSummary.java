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
    String ruleVersion
) {}
