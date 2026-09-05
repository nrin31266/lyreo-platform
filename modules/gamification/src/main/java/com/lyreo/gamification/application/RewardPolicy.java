package com.lyreo.gamification.application;

/** Server-owned reward policy. Client payloads never decide reward amounts. */
public final class RewardPolicy {
    public int lessonActivityDiamonds(String activityType, int serverScore, boolean firstCompletion) {
        if (!firstCompletion || serverScore < 60) return 0;
        return switch (activityType) {
            case "SHADOWING", "DICTATION" -> 2;
            case "GRAMMAR_PRACTICE", "VOCABULARY_PRACTICE" -> 1;
            default -> 0;
        };
    }
}
