package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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
    public Object add(@AuthenticationPrincipal Jwt jwt, @RequestBody AddCardRequest request) {
        UUID learnerId = learnerId(jwt);
        return vocabulary.add(learnerId, request.lexiconEntryId(), request.sourceContextType(), request.sourceContextId());
    }

    @GetMapping("/due")
    public Object due(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
        return vocabulary.due(learnerId(jwt), limit);
    }

    @PostMapping("/cards/{cardId}/review")
    public Object review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID cardId, @RequestBody ReviewRequest request) {
        var result = vocabulary.review(learnerId(jwt), cardId, request.rating());
        return Map.of("cardId", cardId, "nextReviewAt", result.nextReviewAt(), "stability", result.stability(), "difficulty", result.difficulty());
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record AddCardRequest(UUID lexiconEntryId, String sourceContextType, UUID sourceContextId) {}
    public record ReviewRequest(SpacedRepetitionScheduler.Rating rating) {}
}
