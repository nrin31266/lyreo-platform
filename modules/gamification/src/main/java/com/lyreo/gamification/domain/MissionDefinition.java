package com.lyreo.gamification.domain;

import java.util.UUID;

public record MissionDefinition(
    UUID id,
    String code,
    String title,
    Metric metric,
    int target,
    int diamondReward,
    Recurrence recurrence,
    boolean enabled
) {
    public enum Metric {
        LESSON_COMPLETED,
        SHADOWING_SENTENCE,
        VOCABULARY_REVIEW,
        TOEIC_DRILL_COMPLETED
    }

    public enum Recurrence { DAILY, WEEKLY, ONE_TIME }
}
