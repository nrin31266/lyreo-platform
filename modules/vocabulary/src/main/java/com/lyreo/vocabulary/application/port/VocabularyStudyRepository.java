package com.lyreo.vocabulary.application.port;

import com.lyreo.vocabulary.domain.PracticeMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for learner vocabulary catalog, progress, and 6-mode practice sessions. */
public interface VocabularyStudyRepository {

    Optional<UUID> findActiveReleaseId();

    List<PackRow> findVisiblePacks(UUID releaseId, UUID learnerId);

    Optional<PackRow> findVisiblePack(UUID packId);

    List<UnitRow> findVisibleUnits(UUID packId, UUID learnerId);

    Optional<UnitWithPackRow> findVisibleUnit(UUID unitId);

    List<EntryWithProgressRow> findVisibleEntries(UUID unitId, UUID learnerId);

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
        List<SessionRow> all = findSessions(learnerId, unitId, status);
        if (offset >= all.size()) {
            return List.of();
        }
        return all.subList(offset, Math.min(all.size(), offset + limit));
    }

    default long countSessions(UUID learnerId, UUID unitId, String status) {
        return findSessions(learnerId, unitId, status).size();
    }

    int updateAnswers(UUID id, long expectedRevision, String answersJson);

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
        String resultJson,
        long revision,
        Instant createdAt,
        Instant expiresAt,
        Instant submittedAt
    ) {}
}
