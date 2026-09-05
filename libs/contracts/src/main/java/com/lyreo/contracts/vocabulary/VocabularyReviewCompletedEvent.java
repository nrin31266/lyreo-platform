package com.lyreo.contracts.vocabulary;

import java.time.Instant;
import java.util.UUID;

public record VocabularyReviewCompletedEvent(
    UUID learnerId, UUID lexiconEntryId, String rating, boolean correct,
    Instant occurredAt) {}
