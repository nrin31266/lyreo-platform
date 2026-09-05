package com.lyreo.learner.domain;

import java.util.UUID;

public record LearnerProfile(
    UUID learnerId,
    String displayName,
    String currentLevel,
    String goal,
    Integer dailyMinutes,
    String focusArea,
    LearnerPreferences preferences
) {}
