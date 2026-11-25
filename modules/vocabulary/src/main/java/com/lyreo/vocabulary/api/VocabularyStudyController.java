package com.lyreo.vocabulary.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.SessionSummary;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.SessionView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.Resource;
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

/** Learner HTTP endpoints for vocabulary study catalog, authorized media, and 6-mode practice sessions. */
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
            request.limit(),
            idempotencyKey
        );
        return ResponseEntity.created(URI.create("/api/v1/vocabulary/sessions/" + session.id()))
            .body(session);
    }

    @GetMapping("/sessions")
    public List<SessionSummary> listSessions(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID unitId,
        @RequestParam(required = false) String status
    ) {
        return study.listSessions(learnerId(jwt), unitId, status);
    }

    @GetMapping("/sessions/{id}")
    public SessionView getSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return study.getSession(learnerId(jwt), id);
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

    public record StartSessionRequest(
        @NotNull(message = "mode is required")
        PracticeMode mode,
        @Min(1) @Max(50) Integer limit
    ) {}

    public record SaveAnswersRequest(
        @NotNull(message = "revision is required")
        Long revision,
        @NotNull(message = "answers is required")
        Map<UUID, String> answers
    ) {}

    public record SubmitSessionRequest(
        @NotNull(message = "revision is required")
        Long revision
    ) {}
}
