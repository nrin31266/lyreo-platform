package com.lyreo.contracts.grammar;

import java.time.Instant;
import java.util.UUID;

/**
 * Cross-module fact emitted after Lyreo scores a grammar answer on the server.
 *
 * <p>The Grammar module remains owner of the imported bank and attempt history. Other modules
 * consume only this immutable fact for analytics, missions or curriculum projections.</p>
 */
public record GrammarQuestionAnsweredEvent(
    UUID learnerId,
    UUID attemptId,
    UUID questionId,
    UUID topicId,
    UUID subtopicId,
    int difficultyLevel,
    boolean correct,
    Instant occurredAt
) {}
