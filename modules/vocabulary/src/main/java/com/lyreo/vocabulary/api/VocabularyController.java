package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.domain.VocabularyCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
            vocabulary.add(learnerId, request.lexiconEntryId(), request.sourceContextType(), request.sourceContextId())
        );
    }

    @GetMapping("/due")
    public List<VocabularyCardResponse> due(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return vocabulary.due(learnerId(jwt), limit).stream()
            .map(VocabularyCardResponse::from)
            .toList();
    }

    @PostMapping("/cards/{cardId}/review")
    public VocabularyReviewResponse review(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID cardId,
        @Valid @RequestBody ReviewRequest request
    ) {
        var result = vocabulary.review(learnerId(jwt), cardId, request.rating());
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
        @NotNull(message = "lexiconEntryId is required")
        UUID lexiconEntryId,
        String sourceContextType,
        UUID sourceContextId
    ) {}

    public record ReviewRequest(
        @NotNull(message = "rating is required")
        SpacedRepetitionScheduler.Rating rating
    ) {}

    public record VocabularyReviewResponse(
        UUID cardId,
        Instant nextReviewAt,
        double stability,
        double difficulty
    ) {}

    public record VocabularyCardResponse(
        UUID id,
        UUID learnerId,
        UUID lexiconEntryId,
        String sourceContextType,
        UUID sourceContextId,
        Instant nextReviewAt,
        double stability,
        double difficulty,
        int lapseCount,
        int reviewCount
    ) {
        public static VocabularyCardResponse from(VocabularyCard card) {
            return new VocabularyCardResponse(
                card.id(),
                card.learnerId(),
                card.lexiconEntryId(),
                card.sourceContextType(),
                card.sourceContextId(),
                card.nextReviewAt(),
                card.stability(),
                card.difficulty(),
                card.lapseCount(),
                card.reviewCount()
            );
        }
    }
}
