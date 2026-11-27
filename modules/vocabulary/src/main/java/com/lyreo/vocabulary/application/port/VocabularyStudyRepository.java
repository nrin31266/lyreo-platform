package com.lyreo.vocabulary.application.port;

import com.lyreo.vocabulary.domain.PracticeMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for learner vocabulary catalog, progress, flashcards SRS, and practice sessions. */
public interface VocabularyStudyRepository {

    Optional<UUID> findActiveReleaseId();

    List<PackRow> findVisiblePacks(UUID releaseId, UUID learnerId);

    Optional<PackRow> findVisiblePack(UUID packId);

    List<UnitRow> findVisibleUnits(UUID packId, UUID learnerId);

    Optional<UnitWithPackRow> findVisibleUnit(UUID unitId);

    List<EntryWithProgressRow> findVisibleEntries(UUID unitId, UUID learnerId);

    default List<EntryWithProgressRow> findVisibleEntriesByPack(UUID packId, UUID learnerId) {
        return List.of();
    }

    default Optional<FlashcardEntryRow> findVisibleEntryForFlashcard(UUID entryId, UUID learnerId) {
        return Optional.empty();
    }

    default List<FlashcardEntryRow> findFlashcardEntriesByPack(UUID packId, UUID learnerId) {
        return List.of();
    }

    default EntrySrsState lockOrCreateEntryProgressForSrs(
        UUID learnerId,
        UUID entryId,
        UUID unitId,
        UUID packId,
        Instant now
    ) {
        throw new UnsupportedOperationException("lockOrCreateEntryProgressForSrs not implemented");
    }

    default UUID applyEntryFlashcardReview(
        UUID learnerId,
        UUID entryId,
        UUID unitId,
        UUID packId,
        SpacedRepetitionScheduler.Rating rating,
        SpacedRepetitionScheduler.ScheduleResult result,
        Instant reviewedAt
    ) {
        throw new UnsupportedOperationException("applyEntryFlashcardReview not implemented");
    }

    Optional<EntryMediaAccessRow> findVisibleEntryForMedia(UUID entryId);

    void upsertEntryProgress(UUID learnerId, UUID entryId, UUID unitId, UUID packId, boolean correct, Instant at);

    void insertSession(
        UUID id,
        UUID learnerId,
        UUID packId,
        UUID unitId,
        PracticeMode mode,
        String snapshotJson,
        Instant createdAt,
        Instant expiresAt
    );

    Optional<SessionRow> findSession(UUID id, UUID learnerId, boolean forUpdate);

    List<SessionRow> findSessions(UUID learnerId, UUID unitId, String status);

    default List<SessionRow> findSessionsPage(UUID learnerId, UUID unitId, String status, int offset, int limit) {
        return findSessionsPage(learnerId, null, unitId, status, offset, limit);
    }

    default List<SessionRow> findSessionsPage(
        UUID learnerId,
        UUID packId,
        UUID unitId,
        String status,
        int offset,
        int limit
    ) {
        List<SessionRow> all = findSessions(learnerId, unitId, status);
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(all.size(), offset + limit));
    }

    default long countSessions(UUID learnerId, UUID unitId, String status) {
        return countSessions(learnerId, null, unitId, status);
    }

    default long countSessions(UUID learnerId, UUID packId, UUID unitId, String status) {
        return findSessions(learnerId, unitId, status).size();
    }

    int updateAnswers(UUID id, long expectedRevision, String answersJson);

    default int updateQuestionState(UUID id, String questionStateJson) {
        throw new UnsupportedOperationException("updateQuestionState not implemented");
    }

    default int updateAnswersAndQuestionState(
        UUID id,
        long expectedRevision,
        String answersJson,
        String questionStateJson
    ) {
        throw new UnsupportedOperationException("updateAnswersAndQuestionState not implemented");
    }

    int markSubmitted(UUID id, long expectedRevision, String resultJson, Instant submittedAt);

    record PackRow(
        UUID id,
        String titleEn,
        String titleVi,
        long unitCount,
        long entryCount,
        long completedEntries,
        String accessMode,
        String requiredFeatureKey,
        UUID coverImageId
    ) {}

    record UnitRow(
        UUID id,
        UUID packId,
        int position,
        String level,
        String title,
        long entryCount,
        long completedEntries,
        long revision
    ) {}

    record UnitWithPackRow(
        UUID id,
        UUID packId,
        int position,
        String level,
        String title,
        long revision,
        String packAccessMode,
        String packRequiredFeatureKey
    ) {}

    record EntryWithProgressRow(
        UUID id,
        UUID unitId,
        int position,
        String term,
        long revision,
        VocabularyCatalog.LearningContent content,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        String progressStatus
    ) {}

    record FlashcardEntryRow(
        UUID entryId,
        UUID unitId,
        int unitPosition,
        String unitLevel,
        UUID packId,
        int position,
        String term,
        long revision,
        VocabularyCatalog.LearningContent content,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        String packAccessMode,
        String packRequiredFeatureKey,
        Instant srsNextReviewAt,
        double srsStability,
        double srsDifficulty,
        int srsReviewCount,
        int srsLapseCount
    ) {}

    record EntrySrsState(
        int reviewCount,
        int lapseCount,
        double stability,
        double difficulty,
        Instant nextReviewAt
    ) {}

    record EntryMediaAccessRow(
        UUID entryId,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        String packAccessMode,
        String packRequiredFeatureKey
    ) {
        public boolean ownsAsset(UUID assetId) {
            return assetId != null && (
                assetId.equals(imageId)
                || assetId.equals(ukAudioId)
                || assetId.equals(usAudioId)
                || assetId.equals(exampleAudioId)
                || assetId.equals(secondExampleAudioId)
            );
        }
    }

    record SessionRow(
        UUID id,
        UUID learnerId,
        UUID packId,
        UUID unitId,
        PracticeMode mode,
        String snapshotJson,
        String answersJson,
        String questionStateJson,
        String resultJson,
        long revision,
        Instant createdAt,
        Instant expiresAt,
        Instant submittedAt
    ) {
        public SessionRow(
            UUID id,
            UUID learnerId,
            UUID packId,
            UUID unitId,
            PracticeMode mode,
            String snapshotJson,
            String answersJson,
            String resultJson,
            long revision,
            Instant createdAt,
            Instant expiresAt,
            Instant submittedAt
        ) {
            this(
                id, learnerId, packId, unitId, mode, snapshotJson, answersJson,
                "{}", resultJson, revision, createdAt, expiresAt, submittedAt
            );
        }
    }
}
