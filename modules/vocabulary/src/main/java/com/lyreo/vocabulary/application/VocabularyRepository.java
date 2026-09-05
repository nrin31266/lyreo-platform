package com.lyreo.vocabulary.application;

import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VocabularyRepository {
    VocabularyCard addIfAbsent(UUID learnerId, UUID lexiconEntryId, String sourceContextType, UUID sourceContextId, Instant now);
    Optional<VocabularyCard> findCard(UUID learnerId, UUID cardId);
    List<VocabularyCard> due(UUID learnerId, Instant now, int limit);
    void applyReview(UUID cardId, SpacedRepetitionScheduler.Rating rating, SpacedRepetitionScheduler.ScheduleResult result, Instant reviewedAt);
}
