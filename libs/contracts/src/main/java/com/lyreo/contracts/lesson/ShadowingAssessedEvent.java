package com.lyreo.contracts.lesson;

import java.util.UUID;

/** Aggregate assessment evidence for Analytics, with no recording or transcript data. */
public record ShadowingAssessedEvent(
    UUID attemptId,
    UUID learnerId,
    int mismatchedWords,
    int assessedWords,
    Integer fluencyScore
) {}
