package com.lyreo.vocabulary.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.vocabulary.application.port.VocabularyCollectionRepository;
import com.lyreo.vocabulary.domain.VocabularyCollection;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for learner personal vocabulary collections and memberships.
 * Collection management never alters SRS schedules or emits XP/review/mission events.
 */
public class VocabularyCollectionService {

    private final VocabularyCollectionRepository repository;

    public VocabularyCollectionService(VocabularyCollectionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<VocabularyCollection> listCollections(UUID learnerId) {
        repository.ensureDefaultCollection(learnerId);
        return repository.listCollections(learnerId);
    }

    @Transactional
    public VocabularyCollection createCollection(UUID learnerId, String name, String coverPreset) {
        if (name == null || name.isBlank()) {
            throw new RequestValidationException("Collection name is required");
        }
        String cleanName = name.strip();
        String preset = (coverPreset == null || coverPreset.isBlank()) ? "BLUE_BOOK" : coverPreset.strip();
        repository.ensureDefaultCollection(learnerId);
        return repository.createCollection(learnerId, cleanName, preset);
    }

    @Transactional
    public VocabularyCollection updateCollection(
        UUID learnerId,
        UUID collectionId,
        String name,
        String coverPreset
    ) {
        VocabularyCollection existing = repository.findCollection(learnerId, collectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary collection not found"));

        String nextName = existing.name();
        if (name != null) {
            if (name.isBlank()) {
                throw new RequestValidationException("Collection name cannot be blank");
            }
            String stripped = name.strip();
            if (existing.isDefault() && !stripped.equals(existing.name())) {
                throw new StateConflictException("Default collection cannot be renamed");
            }
            nextName = stripped;
        }
        String nextPreset = (coverPreset != null && !coverPreset.isBlank())
            ? coverPreset.strip()
            : existing.coverPreset();

        return repository.updateCollection(learnerId, collectionId, nextName, nextPreset);
    }

    @Transactional
    public void deleteCollection(UUID learnerId, UUID collectionId) {
        VocabularyCollection existing = repository.findCollection(learnerId, collectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary collection not found"));
        if (existing.isDefault()) {
            throw new StateConflictException("Default collection cannot be deleted");
        }
        VocabularyCollection defaultCollection = repository.ensureDefaultCollection(learnerId);
        repository.deleteCollectionMovingOrphans(learnerId, collectionId, defaultCollection.id());
    }

    public VocabularyCollectionRepository.CollectionItemPage listItems(
        UUID learnerId,
        UUID collectionId,
        String query,
        int page,
        int size
    ) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("Invalid page or size");
        }
        repository.findCollection(learnerId, collectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary collection not found"));
        return repository.listItems(learnerId, collectionId, query == null ? "" : query.strip(), page, size);
    }

    @Transactional
    public VocabularyCollection addItems(UUID learnerId, UUID collectionId, List<UUID> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            throw new RequestValidationException("cardIds must not be empty");
        }
        List<UUID> distinct = cardIds.stream().distinct().toList();
        repository.findCollection(learnerId, collectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary collection not found"));
        if (!repository.verifyAllCardsOwned(learnerId, distinct)) {
            throw new ResourceNotFoundException("One or more vocabulary cards not found");
        }
        repository.addItemsToCollection(learnerId, collectionId, distinct);
        return repository.findCollection(learnerId, collectionId).orElseThrow();
    }

    @Transactional
    public void removeItem(UUID learnerId, UUID collectionId, UUID cardId) {
        repository.findCollection(learnerId, collectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary collection not found"));
        repository.removeItemFromCollection(learnerId, collectionId, cardId);
    }

    @Transactional
    public MoveResult moveItems(
        UUID learnerId,
        UUID sourceCollectionId,
        UUID targetCollectionId,
        List<UUID> cardIds
    ) {
        if (cardIds == null || cardIds.isEmpty()) {
            throw new RequestValidationException("cardIds must not be empty");
        }
        if (targetCollectionId == null) {
            throw new RequestValidationException("targetCollectionId is required");
        }
        repository.findCollection(learnerId, sourceCollectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Source vocabulary collection not found"));
        repository.findCollection(learnerId, targetCollectionId)
            .orElseThrow(() -> new ResourceNotFoundException("Target vocabulary collection not found"));
        if (sourceCollectionId.equals(targetCollectionId)) {
            throw new RequestValidationException("Target collection must differ from source collection");
        }
        List<UUID> distinct = cardIds.stream().distinct().toList();
        int moved = repository.moveItems(learnerId, sourceCollectionId, targetCollectionId, distinct);
        return new MoveResult(moved, sourceCollectionId, targetCollectionId);
    }

    public record MoveResult(int movedCount, UUID sourceCollectionId, UUID targetCollectionId) {}
}
