package com.lyreo.vocabulary.application.port;

import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VocabularyRepository {
    VocabularyCard addIfAbsent(UUID learnerId, UUID headwordId, String sourceContextType, UUID sourceContextId, Instant now);
    Optional<VocabularyCard> findCard(UUID learnerId, UUID cardId);
    List<VocabularyCard> due(UUID learnerId, Instant now, int limit);
    void applyReview(UUID cardId, SpacedRepetitionScheduler.Rating rating, SpacedRepetitionScheduler.ScheduleResult result, Instant reviewedAt);

    default void lockLearnerSaveGate(UUID learnerId) {}

    default Optional<VocabularyCard> findCardByDedupKey(UUID learnerId, String dedupKey) {
        return Optional.empty();
    }

    default Optional<VocabularyCard> findCardForUpdate(UUID learnerId, UUID cardId) {
        return findCard(learnerId, cardId);
    }

    default int countCards(UUID learnerId) {
        return 0;
    }

    default VocabularyCard insertSavedCard(SaveCardCommand command, Instant now) {
        throw new UnsupportedOperationException("insertSavedCard not implemented");
    }

    default UUID applyReviewWithId(
        UUID cardId,
        SpacedRepetitionScheduler.Rating rating,
        SpacedRepetitionScheduler.ScheduleResult result,
        Instant reviewedAt
    ) {
        applyReview(cardId, rating, result, reviewedAt);
        return UUID.randomUUID();
    }

    default List<UUID> findCollectionIdsForCard(UUID cardId) {
        return List.of();
    }

    default Optional<EntrySnapshotSeed> findEntrySnapshotSeed(UUID entryId) {
        return Optional.empty();
    }

    default Optional<SavedCardMediaAccess> findSavedCardMediaAccess(UUID learnerId, UUID cardId) {
        return Optional.empty();
    }

    record SaveCardCommand(
        UUID id,
        UUID learnerId,
        UUID headwordId,
        String dedupKey,
        String term,
        String partOfSpeech,
        String meaningVi,
        String definitionEn,
        String ipa,
        String contextSentence,
        String contextTranslationVi,
        String sourceContextType,
        UUID sourceContextId,
        UUID sourceEntryId,
        Integer senseIndex,
        Long sourceRevision,
        UUID audioAssetId,
        UUID imageAssetId,
        String snapshotJson
    ) {}

    record EntrySnapshotSeed(
        UUID entryId,
        String term,
        VocabularyCatalog.LearningContent content,
        long revision,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId
    ) {}

    record SavedCardMediaAccess(
        UUID cardId,
        UUID audioAssetId,
        UUID imageAssetId,
        String packAccessMode,
        String requiredFeatureKey
    ) {}
}
