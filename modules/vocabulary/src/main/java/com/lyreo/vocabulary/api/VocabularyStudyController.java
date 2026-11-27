package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.CheckQuestionResponse;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.FlashcardDeckResponse;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.FlashcardReviewResponse;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.FlashcardScope;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.HintResponse;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.SessionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner HTTP endpoints for vocabulary study catalog, authorized media,
 * flashcard SRS study, and multi-mode / guess-the-word practice sessions.
 */
@RestController
@RequestMapping("/api/v1/vocabulary")
public class VocabularyStudyController {

    private final VocabularyStudyService study;
    private final AppUserProvisioningService users;
    private final AssetStore assets;

    public VocabularyStudyController(
        VocabularyStudyService study,
        AppUserProvisioningService users,
        AssetStore assets
    ) {
        this.study = study;
        this.users = users;
        this.assets = assets;
    }

    @GetMapping("/packs")
    public List<VocabularyStudyService.PackSummary> packs(@AuthenticationPrincipal Jwt jwt) {
        return study.listPacks(learnerId(jwt));
    }

    @GetMapping("/packs/{packId}/cover")
    public ResponseEntity<byte[]> packCover(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packId,
        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch
    ) {
        AssetStore.Asset meta = study.authorizedPackCover(learnerId(jwt), packId);
        String etag = "\"" + meta.id() + "-" + meta.sizeBytes() + "\"";
        if (etag.equals(ifNoneMatch != null ? ifNoneMatch.strip() : null)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .cacheControl(CacheControl.noCache().mustRevalidate())
                .eTag(etag)
                .build();
        }
        byte[] bytes = assets.bytes(meta.id());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(meta.mimeType()))
            .cacheControl(CacheControl.noCache().mustRevalidate())
            .eTag(etag)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes);
    }

    @GetMapping("/packs/{packId}/units")
    public List<VocabularyStudyService.UnitSummary> units(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packId
    ) {
        return study.listUnits(learnerId(jwt), packId);
    }

    @GetMapping("/units/{unitId}")
    public VocabularyStudyService.UnitDetail unitDetail(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID unitId
    ) {
        return study.getUnitDetail(learnerId(jwt), unitId);
    }

    @GetMapping("/entries/{entryId}/media/{assetId}")
    public ResponseEntity<Resource> entryMedia(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID entryId,
        @PathVariable UUID assetId,
        @RequestHeader(value = "Range", required = false) String rangeHeader
    ) {
        AssetStore.Asset asset = study.authorizedMedia(learnerId(jwt), entryId, assetId);
        byte[] bytes = assets.bytes(asset.id());
        return VocabularyMediaStreamer.stream(asset, bytes, rangeHeader);
    }

    @GetMapping("/flashcards")
    public FlashcardDeckResponse flashcards(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID unitId,
        @RequestParam(required = false) UUID packId,
        @RequestParam(defaultValue = "DUE_AND_NEW") FlashcardScope scope,
        @RequestParam(defaultValue = "30") @Min(1) @Max(200) int limit
    ) {
        return study.getFlashcardDeck(learnerId(jwt), unitId, packId, scope, limit);
    }

    @PostMapping("/entries/{entryId}/flashcard-review")
    public FlashcardReviewResponse reviewFlashcard(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID entryId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody FlashcardReviewRequest request
    ) {
        return study.reviewFlashcard(
            learnerId(jwt),
            entryId,
            request.rating(),
            request.expectedReviewCount(),
            idempotencyKey
        );
    }

    @PostMapping("/units/{unitId}/sessions")
    public ResponseEntity<SessionView> startSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID unitId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody StartSessionRequest request
    ) {
        SessionView session = study.startSession(
            learnerId(jwt),
            unitId,
            request.mode(),
            request.modes(),
            request.limit(),
            request.entryIds(),
            idempotencyKey
        );
        return ResponseEntity.created(URI.create("/api/v1/vocabulary/sessions/" + session.id()))
            .body(session);
    }

    @PostMapping("/packs/{packId}/sessions")
    public ResponseEntity<SessionView> startPackSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID packId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody StartSessionRequest request
    ) {
        SessionView session = study.startPackSession(
            learnerId(jwt),
            packId,
            request.mode(),
            request.modes(),
            request.limit(),
            request.entryIds(),
            idempotencyKey
        );
        return ResponseEntity.created(URI.create("/api/v1/vocabulary/sessions/" + session.id()))
            .body(session);
    }

    @GetMapping("/sessions")
    public VocabularyStudyService.SessionPage listSessions(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID packId,
        @RequestParam(required = false) UUID unitId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return study.listSessionsPage(learnerId(jwt), packId, unitId, status, page, size);
    }

    @GetMapping("/sessions/{id}")
    public SessionView getSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return study.getSession(learnerId(jwt), id);
    }

    @PostMapping("/sessions/{id}/questions/{questionId}/hint")
    public HintResponse requestHint(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @PathVariable UUID questionId
    ) {
        return study.requestHint(learnerId(jwt), id, questionId);
    }

    @PostMapping("/sessions/{id}/questions/{questionId}/check")
    public CheckQuestionResponse checkQuestion(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @PathVariable UUID questionId,
        @Valid @RequestBody CheckQuestionRequest request
    ) {
        return study.checkQuestion(
            learnerId(jwt),
            id,
            questionId,
            request.revision(),
            request.answer(),
            Boolean.TRUE.equals(request.giveUp())
        );
    }

    @PutMapping("/sessions/{id}/answers")
    public SessionView saveAnswers(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody SaveAnswersRequest request
    ) {
        return study.saveAnswers(learnerId(jwt), id, request.revision(), request.answers());
    }

    @PostMapping("/sessions/{id}/submit")
    public SessionView submitSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody SubmitSessionRequest request
    ) {
        return study.submitSession(learnerId(jwt), id, request.revision(), idempotencyKey);
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record FlashcardReviewRequest(
        @NotNull(message = "rating is required")
        SpacedRepetitionScheduler.Rating rating,
        @Min(0)
        Integer expectedReviewCount
    ) {
        public FlashcardReviewRequest(SpacedRepetitionScheduler.Rating rating) {
            this(rating, null);
        }
    }

    public record StartSessionRequest(
        PracticeMode mode,
        @Size(max = 10)
        List<PracticeMode> modes,
        @Min(1) @Max(100)
        Integer limit,
        @Size(max = 100)
        List<@NotNull UUID> entryIds
    ) {
        public StartSessionRequest(PracticeMode mode, Integer limit) {
            this(mode, null, limit, null);
        }

        public StartSessionRequest(PracticeMode mode, List<PracticeMode> modes, Integer limit) {
            this(mode, modes, limit, null);
        }
    }

    public record CheckQuestionRequest(
        @NotNull(message = "revision is required")
        @Min(0)
        Long revision,
        @Size(max = 500)
        String answer,
        Boolean giveUp
    ) {}

    public record SaveAnswersRequest(
        @NotNull(message = "revision is required")
        @Min(0)
        Long revision,
        @NotNull(message = "answers is required")
        @Size(max = 100)
        Map<UUID, @Size(max = 500) String> answers
    ) {}

    public record SubmitSessionRequest(
        @NotNull(message = "revision is required")
        @Min(0)
        Long revision
    ) {}
}
