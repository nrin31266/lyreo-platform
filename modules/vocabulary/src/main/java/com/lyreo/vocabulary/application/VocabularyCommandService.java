package com.lyreo.vocabulary.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StaleRevisionException;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyCollectionRepository;
import com.lyreo.vocabulary.application.port.VocabularyRepository;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

public class VocabularyCommandService {
    private static final FeatureKey SAVED_CARDS_FEATURE = FeatureKey.of("vocabulary.saved-cards");

    private final VocabularyRepository repository;
    private final SpacedRepetitionScheduler scheduler;
    private final ApplicationEventPublisher events;
    private final OperationIdempotencyService idempotency;
    private final EntitlementService entitlements;
    private final LexiconLinkService lexiconLinkService;
    private final VocabularyCollectionRepository collections;
    private final AssetStore assets;
    private final ObjectMapper json;

    public VocabularyCommandService(
        VocabularyRepository repository,
        SpacedRepetitionScheduler scheduler,
        ApplicationEventPublisher events
    ) {
        this(repository, scheduler, events, null, null, null, null, null, new ObjectMapper());
    }

    public VocabularyCommandService(
        VocabularyRepository repository,
        SpacedRepetitionScheduler scheduler,
        ApplicationEventPublisher events,
        OperationIdempotencyService idempotency,
        EntitlementService entitlements,
        LexiconLinkService lexiconLinkService,
        VocabularyCollectionRepository collections,
        AssetStore assets,
        ObjectMapper json
    ) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.events = events;
        this.idempotency = idempotency;
        this.entitlements = entitlements;
        this.lexiconLinkService = lexiconLinkService;
        this.collections = collections;
        this.assets = assets;
        this.json = json != null ? json : new ObjectMapper();
    }

    @Transactional
    public VocabularyCard add(
        UUID learnerId,
        UUID headwordId,
        String contextType,
        UUID contextId
    ) {
        Instant now = Instant.now();
        repository.lockLearnerSaveGate(learnerId);
        String dedupKey = "LEXICON_HEADWORD:" + headwordId;
        Optional<VocabularyCard> existing = repository.findCardByDedupKey(learnerId, dedupKey);
        if (existing.isPresent()) {
            VocabularyCard card = existing.get();
            if (collections != null && repository.findCollectionIdsForCard(card.id()).isEmpty()) {
                UUID defaultColId = collections.ensureDefaultCollection(learnerId).id();
                collections.attachCardToCollections(card.id(), List.of(defaultColId));
            }
            return card;
        }
        if (entitlements != null) {
            int currentCount = repository.countCards(learnerId);
            entitlements.requireCapacity(learnerId, SAVED_CARDS_FEATURE, currentCount, "add-card:" + headwordId);
        }
        VocabularyCard created = repository.addIfAbsent(learnerId, headwordId, contextType, contextId, now);
        if (collections != null) {
            UUID defaultColId = collections.ensureDefaultCollection(learnerId).id();
            collections.attachCardToCollections(created.id(), List.of(defaultColId));
        }
        return created;
    }

    @Transactional
    public SavedItemResult saveItem(UUID learnerId, SaveItemCommand command, String idempotencyKey) {
        if (command == null) {
            throw new RequestValidationException("Request payload is required");
        }
        if (command.collectionIds() != null && !command.collectionIds().isEmpty() && collections != null) {
            if (!collections.verifyAllCollectionsOwned(learnerId, command.collectionIds())) {
                throw new ResourceNotFoundException("Vocabulary collection not found");
            }
        }

        EnrichedSaveInput input = enrich(command);
        String dedupKey = buildDedupKey(input);
        String fingerprint = buildSaveFingerprint(input, dedupKey, command.collectionIds());

        return executeIdempotent(
            learnerId,
            "vocabulary.saved-items.save",
            idempotencyKey,
            fingerprint,
            SavedItemResult.class,
            () -> doSaveItem(learnerId, input, dedupKey, command.collectionIds(), idempotencyKey)
        );
    }

    private SavedItemResult doSaveItem(
        UUID learnerId,
        EnrichedSaveInput input,
        String dedupKey,
        List<UUID> requestedCollections,
        String idempotencyKey
    ) {
        repository.lockLearnerSaveGate(learnerId);

        Optional<VocabularyCard> existing = repository.findCardByDedupKey(learnerId, dedupKey);
        VocabularyCard card;
        if (existing.isPresent()) {
            card = existing.get();
            attachCollectionsForExisting(learnerId, card.id(), requestedCollections);
        } else {
            UUID newId = UUID.randomUUID();
            if (entitlements != null) {
                int currentCount = repository.countCards(learnerId);
                String opId = (idempotencyKey != null && !idempotencyKey.isBlank())
                    ? idempotencyKey.trim()
                    : "save-item:" + newId;
                entitlements.requireCapacity(learnerId, SAVED_CARDS_FEATURE, currentCount, opId);
            }
            String snapshotJson = writeSnapshotJson(input);
            card = repository.insertSavedCard(new VocabularyRepository.SaveCardCommand(
                newId,
                learnerId,
                input.headwordId(),
                dedupKey,
                input.term(),
                input.partOfSpeech(),
                input.meaningVi(),
                input.definitionEn(),
                input.ipa(),
                input.contextSentence(),
                input.contextTranslationVi(),
                input.sourceContextType(),
                input.sourceContextId(),
                input.sourceEntryId(),
                input.senseIndex(),
                input.sourceRevision(),
                input.audioAssetId(),
                input.imageAssetId(),
                snapshotJson
            ), Instant.now());
            attachCollectionsForNew(learnerId, card.id(), requestedCollections);
        }

        List<UUID> collectionIds = repository.findCollectionIdsForCard(card.id());
        return SavedItemResult.from(card, collectionIds);
    }

    private void attachCollectionsForExisting(UUID learnerId, UUID cardId, List<UUID> requestedCollections) {
        if (collections == null) {
            return;
        }
        if (requestedCollections != null && !requestedCollections.isEmpty()) {
            collections.attachCardToCollections(cardId, requestedCollections);
        } else if (repository.findCollectionIdsForCard(cardId).isEmpty()) {
            UUID defaultColId = collections.ensureDefaultCollection(learnerId).id();
            collections.attachCardToCollections(cardId, List.of(defaultColId));
        }
    }

    private void attachCollectionsForNew(UUID learnerId, UUID cardId, List<UUID> requestedCollections) {
        if (collections == null) {
            return;
        }
        if (requestedCollections != null && !requestedCollections.isEmpty()) {
            collections.attachCardToCollections(cardId, requestedCollections);
        } else {
            UUID defaultColId = collections.ensureDefaultCollection(learnerId).id();
            collections.attachCardToCollections(cardId, List.of(defaultColId));
        }
    }

    public List<VocabularyCard> due(UUID learnerId, int limit) {
        return repository.due(learnerId, Instant.now(), Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public SpacedRepetitionScheduler.ScheduleResult review(
        UUID learnerId,
        UUID cardId,
        SpacedRepetitionScheduler.Rating rating
    ) {
        return review(learnerId, cardId, rating, null, null);
    }

    @Transactional
    public SpacedRepetitionScheduler.ScheduleResult review(
        UUID learnerId,
        UUID cardId,
        SpacedRepetitionScheduler.Rating rating,
        Integer expectedReviewCount,
        String idempotencyKey
    ) {
        if (expectedReviewCount != null && expectedReviewCount < 0) {
            throw new RequestValidationException("expectedReviewCount must be >= 0");
        }
        String fingerprint = cardId + "|" + rating.name() + "|" + expectedReviewCount;
        return executeIdempotent(
            learnerId,
            "vocabulary.card.review",
            idempotencyKey,
            fingerprint,
            SpacedRepetitionScheduler.ScheduleResult.class,
            () -> {
                VocabularyCard card = repository.findCardForUpdate(learnerId, cardId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vocabulary card not found: " + cardId));
                if (expectedReviewCount != null && card.reviewCount() != expectedReviewCount) {
                    throw new StaleRevisionException(
                        "Vocabulary card review count changed; reload before reviewing.",
                        (long) card.reviewCount()
                    );
                }
                Instant at = Instant.now();
                var result = scheduler.schedule(card, rating, at);
                UUID reviewId = repository.applyReviewWithId(card.id(), rating, result, at);
                events.publishEvent(new VocabularyReviewCompletedEvent(
                    reviewId,
                    learnerId,
                    card.id(),
                    card.headwordId(),
                    rating.name(),
                    rating != SpacedRepetitionScheduler.Rating.AGAIN,
                    at
                ));
                return result;
            }
        );
    }

    public AssetStore.Asset authorizedCardMedia(UUID learnerId, UUID cardId, UUID assetId) {
        var access = repository.findSavedCardMediaAccess(learnerId, cardId)
            .orElseThrow(() -> new ResourceNotFoundException("Saved vocabulary card not found"));
        boolean matches = assetId != null
            && (assetId.equals(access.audioAssetId()) || assetId.equals(access.imageAssetId()));
        if (!matches) {
            throw new ResourceNotFoundException("Vocabulary card media not found");
        }
        if ("FEATURE".equals(access.packAccessMode()) && access.requiredFeatureKey() != null && entitlements != null) {
            entitlements.requireFeature(learnerId, FeatureKey.of(access.requiredFeatureKey()));
        }
        if (assets == null) {
            throw new ResourceNotFoundException("Media store unavailable");
        }
        return assets.get(assetId);
    }

    private <T> T executeIdempotent(
        UUID learnerId,
        String operation,
        String idempotencyKey,
        String fingerprint,
        Class<T> responseType,
        java.util.function.Supplier<T> action
    ) {
        if (idempotency == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return action.get();
        }
        return idempotency.execute(learnerId, operation, idempotencyKey, fingerprint, responseType, action);
    }

    private EnrichedSaveInput enrich(SaveItemCommand cmd) {
        String term = cleanNullable(cmd.term());
        String pos = cleanNullable(cmd.partOfSpeech());
        String meaningVi = cleanNullable(cmd.meaningVi());
        String definitionEn = cleanNullable(cmd.definitionEn());
        String ipa = cleanNullable(cmd.ipa());
        String contextSentence = cleanNullable(cmd.contextSentence());
        String contextTranslationVi = cleanNullable(cmd.contextTranslationVi());
        Long sourceRevision = cmd.sourceRevision();
        UUID audioAssetId = cmd.audioAssetId();
        UUID imageAssetId = cmd.imageAssetId();

        if (cmd.sourceEntryId() != null) {
            var seedOpt = repository.findEntrySnapshotSeed(cmd.sourceEntryId());
            if (seedOpt.isPresent()) {
                var seed = seedOpt.get();
                if (term == null) {
                    term = cleanNullable(seed.term());
                }
                if (sourceRevision == null) {
                    sourceRevision = seed.revision();
                }
                if (imageAssetId == null) {
                    imageAssetId = seed.imageId();
                }
                if (audioAssetId == null) {
                    audioAssetId = seed.usAudioId() != null ? seed.usAudioId() : seed.ukAudioId();
                }
                VocabularyCatalog.LearningContent content = seed.content();
                if (content != null) {
                    if (ipa == null) {
                        ipa = cleanNullable(content.ipaUs() != null ? content.ipaUs() : content.ipaUk());
                    }
                    int idx = cmd.senseIndex() != null ? cmd.senseIndex() : 0;
                    if (content.senses() != null && idx >= 0 && idx < content.senses().size()) {
                        VocabularyCatalog.Sense sense = content.senses().get(idx);
                        if (pos == null) {
                            pos = cleanNullable(sense.pos());
                        }
                        if (meaningVi == null) {
                            meaningVi = cleanNullable(sense.meaningVi());
                        }
                        if (definitionEn == null) {
                            definitionEn = cleanNullable(sense.definitionEn());
                        }
                    }
                    if (contextSentence == null && content.examples() != null && !content.examples().isEmpty()) {
                        contextSentence = cleanNullable(content.examples().getFirst());
                    }
                    if (contextTranslationVi == null && content.examplesVi() != null && !content.examplesVi().isEmpty()) {
                        contextTranslationVi = cleanNullable(content.examplesVi().getFirst());
                    }
                }
            }
        }

        if (term == null && cmd.headwordId() == null) {
            throw new RequestValidationException("term or sourceEntryId or headwordId is required");
        }

        UUID headwordId = cmd.headwordId();
        if (headwordId == null && term != null && lexiconLinkService != null) {
            headwordId = lexiconLinkService.resolve(term).orElse(null);
        }

        return new EnrichedSaveInput(
            headwordId,
            term != null ? term : "unknown",
            pos,
            meaningVi,
            definitionEn,
            ipa,
            contextSentence,
            contextTranslationVi,
            cleanNullable(cmd.sourceContextType()),
            cmd.sourceContextId(),
            cmd.sourceEntryId(),
            cmd.senseIndex(),
            cmd.lexiconSenseId(),
            sourceRevision,
            audioAssetId,
            imageAssetId
        );
    }

    private static String buildDedupKey(EnrichedSaveInput input) {
        if (input.sourceEntryId() != null && input.senseIndex() != null) {
            return "ENTRY:" + input.sourceEntryId() + ":SENSE:" + input.senseIndex();
        }
        if (input.lexiconSenseId() != null) {
            return "LEXICON_SENSE:" + input.lexiconSenseId();
        }
        if (input.senseIndex() != null) {
            return "TERM:" + norm(input.term()) + ":SENSE:" + input.senseIndex()
                + ":SRC:" + nullSafe(input.sourceContextType()) + ":" + nullSafe(input.sourceContextId());
        }
        String basis = norm(input.term()) + "|" + norm(input.partOfSpeech())
            + "|" + norm(input.meaningVi()) + "|" + norm(input.definitionEn());
        return "SENSE_FP:" + sha256(basis);
    }

    private static String buildSaveFingerprint(
        EnrichedSaveInput input,
        String dedupKey,
        List<UUID> collectionIds
    ) {
        String cols = collectionIds == null ? "" : collectionIds.stream()
            .filter(Objects::nonNull)
            .map(UUID::toString)
            .sorted()
            .reduce((a, b) -> a + "," + b)
            .orElse("");
        return dedupKey + "|" + norm(input.term()) + "|" + norm(input.meaningVi())
            + "|" + norm(input.definitionEn()) + "|" + norm(input.contextSentence()) + "|" + cols;
    }

    private String writeSnapshotJson(EnrichedSaveInput input) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("term", input.term());
        if (input.partOfSpeech() != null) map.put("partOfSpeech", input.partOfSpeech());
        if (input.meaningVi() != null) map.put("meaningVi", input.meaningVi());
        if (input.definitionEn() != null) map.put("definitionEn", input.definitionEn());
        if (input.ipa() != null) map.put("ipa", input.ipa());
        if (input.contextSentence() != null) map.put("contextSentence", input.contextSentence());
        if (input.contextTranslationVi() != null) map.put("contextTranslationVi", input.contextTranslationVi());
        if (input.sourceEntryId() != null) map.put("sourceEntryId", input.sourceEntryId().toString());
        if (input.senseIndex() != null) map.put("senseIndex", input.senseIndex());
        if (input.lexiconSenseId() != null) map.put("lexiconSenseId", input.lexiconSenseId().toString());
        if (input.sourceRevision() != null) map.put("sourceRevision", input.sourceRevision());
        return json.writeValueAsString(map);
    }

    private static String cleanNullable(String s) {
        if (s == null) return null;
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String nullSafe(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record SaveItemCommand(
        UUID headwordId,
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
        UUID lexiconSenseId,
        Long sourceRevision,
        UUID audioAssetId,
        UUID imageAssetId,
        List<UUID> collectionIds
    ) {}

    private record EnrichedSaveInput(
        UUID headwordId,
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
        UUID lexiconSenseId,
        Long sourceRevision,
        UUID audioAssetId,
        UUID imageAssetId
    ) {}

    public record SavedItemResult(
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
        Instant nextReviewAt,
        double stability,
        double difficulty,
        int lapseCount,
        int reviewCount,
        List<UUID> collectionIds
    ) {
        public static SavedItemResult from(VocabularyCard card, List<UUID> collectionIds) {
            return new SavedItemResult(
                card.id(),
                card.learnerId(),
                card.headwordId(),
                card.dedupKey(),
                card.term(),
                card.partOfSpeech(),
                card.meaningVi(),
                card.definitionEn(),
                card.ipa(),
                card.contextSentence(),
                card.contextTranslationVi(),
                card.sourceContextType(),
                card.sourceContextId(),
                card.sourceEntryId(),
                card.senseIndex(),
                card.sourceRevision(),
                card.audioAssetId(),
                card.imageAssetId(),
                card.nextReviewAt(),
                card.stability(),
                card.difficulty(),
                card.lapseCount(),
                card.reviewCount(),
                collectionIds != null ? collectionIds : List.of()
            );
        }
    }
}
