package com.lyreo.contracts.curriculum;

import java.time.Instant;
import java.util.UUID;

public record CurriculumItemCompletedEvent(
    UUID learnerId, UUID curriculumItemId, String contentType,
    UUID contentReferenceId, Instant occurredAt) {}
