package com.lyreo.vocabulary.domain;

import java.time.Instant;
import java.util.UUID;

/** Learner-owned personal vocabulary collection. */
public record VocabularyCollection(
    UUID id,
    UUID learnerId,
    String name,
    boolean isDefault,
    String coverPreset,
    Instant createdAt,
    Instant updatedAt,
    long itemCount
) {}
