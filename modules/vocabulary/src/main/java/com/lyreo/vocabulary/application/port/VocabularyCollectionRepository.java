package com.lyreo.vocabulary.application.port;

import com.lyreo.vocabulary.domain.VocabularyCard;
import com.lyreo.vocabulary.domain.VocabularyCollection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for learner personal vocabulary collections and collection-item memberships. */
public interface VocabularyCollectionRepository {

    VocabularyCollection ensureDefaultCollection(UUID learnerId);

    List<VocabularyCollection> listCollections(UUID learnerId);

    Optional<VocabularyCollection> findCollection(UUID learnerId, UUID collectionId);

    VocabularyCollection createCollection(UUID learnerId, String name, String coverPreset);

    VocabularyCollection updateCollection(UUID learnerId, UUID collectionId, String name, String coverPreset);

    void deleteCollectionMovingOrphans(UUID learnerId, UUID collectionId, UUID defaultCollectionId);

    CollectionItemPage listItems(UUID learnerId, UUID collectionId, String query, int page, int size);

    void attachCardToCollections(UUID cardId, List<UUID> collectionIds);

    void addItemsToCollection(UUID learnerId, UUID collectionId, List<UUID> cardIds);

    boolean verifyAllCollectionsOwned(UUID learnerId, List<UUID> collectionIds);

    boolean verifyAllCardsOwned(UUID learnerId, List<UUID> cardIds);

    void removeItemFromCollection(UUID learnerId, UUID collectionId, UUID cardId);

    int moveItems(UUID learnerId, UUID sourceCollectionId, UUID targetCollectionId, List<UUID> cardIds);

    record CollectionItemRow(
        VocabularyCard card,
        Instant addedAt,
        List<UUID> collectionIds
    ) {}

    record CollectionItemPage(
        List<CollectionItemRow> items,
        int page,
        int size,
        long total,
        boolean hasMore
    ) {}
}
