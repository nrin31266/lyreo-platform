package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.VocabularyCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyController {
    private final VocabularyCommandService vocabulary;
    private final AppUserProvisioningService users;

    public VocabularyController(VocabularyCommandService vocabulary, AppUserProvisioningService users) {
        this.vocabulary = vocabulary;
        this.users = users;
    }

    @PostMapping("/cards")
    public VocabularyCardResponse add(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody AddCardRequest request
    ) {
        UUID learnerId = learnerId(jwt);
        return VocabularyCardResponse.from(
            vocabulary.add(learnerId, request.headwordId(), request.sourceContextType(), request.sourceContextId())
        );
    }

    @PostMapping("/saved-items")
    public VocabularyCommandService.SavedItemResult saveItem(
        @AuthenticationPrincipal Jwt jwt,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody SaveVocabularyItemRequest request
    ) {
        UUID learnerId = learnerId(jwt);
        return vocabulary.saveItem(learnerId, request.toCommand(), idempotencyKey);
    }

    @GetMapping("/due")
    public DueCardsPageResponse due(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(required = false) @Min(1) @Max(100) Integer size,
        @RequestParam(required = false) @Min(1) @Max(100) Integer limit
    ) {
        int effectiveSize = size != null ? size : (limit != null ? limit : 20);
        VocabularyCommandService.DuePage duePage = vocabulary.duePage(learnerId(jwt), page, effectiveSize);
        return new DueCardsPageResponse(
            duePage.items().stream().map(VocabularyCardResponse::from).toList(),
            duePage.total(),
            duePage.page(),
            duePage.size(),
            duePage.hasMore()
        );
    }

    @DeleteMapping("/cards/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID cardId
    ) {
        vocabulary.deleteCard(learnerId(jwt), cardId);
    }

    @PostMapping("/cards/{cardId}/review")
    public VocabularyReviewResponse review(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID cardId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody ReviewRequest request
    ) {
        UUID learnerId = learnerId(jwt);
        boolean hasIdempotency = idempotencyKey != null && !idempotencyKey.isBlank();
        var result = (!hasIdempotency && request.expectedReviewCount() == null)
            ? vocabulary.review(learnerId, cardId, request.rating())
            : vocabulary.review(learnerId, cardId, request.rating(), request.expectedReviewCount(), idempotencyKey);
        return new VocabularyReviewResponse(
            cardId,
            result.nextReviewAt(),
            result.stability(),
            result.difficulty()
        );
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record AddCardRequest(
        @NotNull(message = "headwordId is required")
        UUID headwordId,
        String sourceContextType,
        UUID sourceContextId
    ) {}

    public record SaveVocabularyItemRequest(
        UUID headwordId,
        @Size(max = 200) String term,
        @Size(max = 64) String partOfSpeech,
        @Size(max = 1000) String meaningVi,
        @Size(max = 1000) String definitionEn,
        @Size(max = 120) String ipa,
        @Size(max = 1000) String contextSentence,
        @Size(max = 1000) String contextTranslationVi,
        @Size(max = 64) String sourceContextType,
        UUID sourceContextId,
        UUID sourceEntryId,
        @Min(0) @Max(50) Integer senseIndex,
        UUID lexiconSenseId,
        @Min(0) Long sourceRevision,
        UUID audioAssetId,
        UUID imageAssetId,
        @Size(max = 50) List<UUID> collectionIds
    ) {
        VocabularyCommandService.SaveItemCommand toCommand() {
            return new VocabularyCommandService.SaveItemCommand(
                headwordId,
                term,
                partOfSpeech,
                meaningVi,
                definitionEn,
                ipa,
                contextSentence,
                contextTranslationVi,
                sourceContextType,
                sourceContextId,
                sourceEntryId,
                senseIndex,
                lexiconSenseId,
                sourceRevision,
                audioAssetId,
                imageAssetId,
                collectionIds
            );
        }
    }

    public record ReviewRequest(
        @NotNull(message = "rating is required")
        SpacedRepetitionScheduler.Rating rating,
        @Min(0) Integer expectedReviewCount
    ) {
        public ReviewRequest(SpacedRepetitionScheduler.Rating rating) {
            this(rating, null);
        }
    }

    public record VocabularyReviewResponse(
        UUID cardId,
        Instant nextReviewAt,
        double stability,
        double difficulty
    ) {}

    public record DueCardsPageResponse(
        List<VocabularyCardResponse> items,
        long total,
        int page,
        int size,
        boolean hasMore
    ) {}

    public record VocabularyCardResponse(
        UUID id,
        UUID learnerId,
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
        UUID audioAssetId,
        UUID imageAssetId,
        String audioUrl,
        String imageUrl,
        Instant nextReviewAt,
        double stability,
        double difficulty,
        int lapseCount,
        int reviewCount
    ) {
        public VocabularyCardResponse(
            UUID id,
            UUID learnerId,
            UUID headwordId,
            String sourceContextType,
            UUID sourceContextId,
            Instant nextReviewAt,
            double stability,
            double difficulty,
            int lapseCount,
            int reviewCount
        ) {
            this(
                id, learnerId, headwordId,
                null, null, null, null, null, null, null,
                sourceContextType, sourceContextId, null, null, null, null, null, null,
                nextReviewAt, stability, difficulty, lapseCount, reviewCount
            );
        }

        public static VocabularyCardResponse from(VocabularyCard card) {
            return new VocabularyCardResponse(
                card.id(),
                card.learnerId(),
                card.headwordId(),
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
                card.audioAssetId(),
                card.imageAssetId(),
                card.audioAssetId() != null
                    ? "/api/v1/vocabulary/saved-items/" + card.id() + "/media/" + card.audioAssetId()
                    : null,
                card.imageAssetId() != null
                    ? "/api/v1/vocabulary/saved-items/" + card.id() + "/media/" + card.imageAssetId()
                    : null,
                card.nextReviewAt(),
                card.stability(),
                card.difficulty(),
                card.lapseCount(),
                card.reviewCount()
            );
        }
    }
}
