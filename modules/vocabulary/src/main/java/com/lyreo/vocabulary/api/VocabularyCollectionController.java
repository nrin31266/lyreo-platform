package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.VocabularyCollectionService;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.application.port.VocabularyCollectionRepository;
import com.lyreo.vocabulary.domain.VocabularyCollection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Learner HTTP endpoints for personal vocabulary collections and saved-item media access. */
@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyCollectionController {

    private final VocabularyCollectionService collections;
    private final VocabularyCommandService vocabulary;
    private final AppUserProvisioningService users;
    private final AssetStore assets;

    public VocabularyCollectionController(
        VocabularyCollectionService collections,
        VocabularyCommandService vocabulary,
        AppUserProvisioningService users,
        AssetStore assets
    ) {
        this.collections = collections;
        this.vocabulary = vocabulary;
        this.users = users;
        this.assets = assets;
    }

    @GetMapping("/collections")
    public List<CollectionResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return collections.listCollections(learnerId(jwt)).stream()
            .map(CollectionResponse::from)
            .toList();
    }

    @PostMapping("/collections")
    public ResponseEntity<CollectionResponse> create(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateCollectionRequest request
    ) {
        VocabularyCollection created = collections.createCollection(
            learnerId(jwt),
            request.name(),
            request.coverPreset()
        );
        return ResponseEntity.created(URI.create("/api/v1/vocabulary/collections/" + created.id()))
            .body(CollectionResponse.from(created));
    }

    @PatchMapping("/collections/{id}")
    public CollectionResponse update(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody UpdateCollectionRequest request
    ) {
        return CollectionResponse.from(collections.updateCollection(
            learnerId(jwt),
            id,
            request.name(),
            request.coverPreset()
        ));
    }

    @DeleteMapping("/collections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        collections.deleteCollection(learnerId(jwt), id);
    }

    @GetMapping("/collections/{id}/items")
    public CollectionItemsPageResponse items(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @RequestParam(defaultValue = "") @Size(max = 120) String q,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        VocabularyCollectionRepository.CollectionItemPage result =
            collections.listItems(learnerId(jwt), id, q, page, size);
        return CollectionItemsPageResponse.from(result);
    }

    @PostMapping("/collections/{id}/items")
    public CollectionResponse addItems(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody ModifyCollectionItemsRequest request
    ) {
        return CollectionResponse.from(collections.addItems(learnerId(jwt), id, request.cardIds()));
    }

    @DeleteMapping("/collections/{id}/items/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @PathVariable UUID cardId
    ) {
        collections.removeItem(learnerId(jwt), id, cardId);
    }

    @PostMapping("/collections/{id}/move")
    public VocabularyCollectionService.MoveResult moveItems(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody MoveItemsRequest request
    ) {
        return collections.moveItems(learnerId(jwt), id, request.targetCollectionId(), request.cardIds());
    }

    @GetMapping("/saved-items/{cardId}/media/{assetId}")
    public ResponseEntity<Resource> savedItemMedia(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID cardId,
        @PathVariable UUID assetId,
        @RequestHeader(value = "Range", required = false) String rangeHeader
    ) {
        AssetStore.Asset asset = vocabulary.authorizedCardMedia(learnerId(jwt), cardId, assetId);
        byte[] bytes = assets.bytes(asset.id());
        return VocabularyMediaStreamer.stream(asset, bytes, rangeHeader);
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record CreateCollectionRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 32) String coverPreset
    ) {}

    public record UpdateCollectionRequest(
        @Size(max = 120) String name,
        @Size(max = 32) String coverPreset
    ) {}

    public record ModifyCollectionItemsRequest(
        @NotEmpty @Size(max = 100) List<@NotNull UUID> cardIds
    ) {}

    public record MoveItemsRequest(
        @NotEmpty @Size(max = 100) List<@NotNull UUID> cardIds,
        @NotNull UUID targetCollectionId
    ) {}

    public record CollectionResponse(
        UUID id,
        String name,
        boolean isDefault,
        String coverPreset,
        long itemCount,
        Instant createdAt,
        Instant updatedAt
    ) {
        static CollectionResponse from(VocabularyCollection col) {
            return new CollectionResponse(
                col.id(),
                col.name(),
                col.isDefault(),
                col.coverPreset(),
                col.itemCount(),
                col.createdAt(),
                col.updatedAt()
            );
        }
    }

    public record CollectionItemView(
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
        Instant addedAt,
        List<UUID> collectionIds
    ) {
        static CollectionItemView from(VocabularyCollectionRepository.CollectionItemRow row) {
            var card = row.card();
            return new CollectionItemView(
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
                row.addedAt(),
                row.collectionIds()
            );
        }
    }

    public record CollectionItemsPageResponse(
        List<CollectionItemView> items,
        int page,
        int size,
        long total,
        boolean hasMore
    ) {
        static CollectionItemsPageResponse from(VocabularyCollectionRepository.CollectionItemPage page) {
            return new CollectionItemsPageResponse(
                page.items().stream().map(CollectionItemView::from).toList(),
                page.page(),
                page.size(),
                page.total(),
                page.hasMore()
            );
        }
    }
}
