package com.lyreo.gamification.domain;

import java.time.Instant;

public record RewardPolicyConfig(
    String ruleVersion,
    boolean directLessonActivityDiamondsEnabled,
    Instant cutoverEffectiveAt,
    int xpPerLevel
) {
    public static final String DEFAULT_RULE_VERSION = "dev-v1";
    public static final Instant DEFAULT_CUTOVER = Instant.parse("2026-11-25T00:00:00Z");
    public static final int DEFAULT_XP_PER_LEVEL = 100;

    public static RewardPolicyConfig defaults() {
        return new RewardPolicyConfig(
            DEFAULT_RULE_VERSION,
            false,
            DEFAULT_CUTOVER,
            DEFAULT_XP_PER_LEVEL
        );
    }

    public int normalizedXpPerLevel() {
        return xpPerLevel > 0 ? xpPerLevel : DEFAULT_XP_PER_LEVEL;
    }

    public String normalizedRuleVersion() {
        return (ruleVersion == null || ruleVersion.isBlank()) ? DEFAULT_RULE_VERSION : ruleVersion.trim();
    }
}
