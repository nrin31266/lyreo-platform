package com.lyreo.grammar.api;

import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.grammar.application.PublishedGrammarCatalogs;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.CatalogPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/grammar")
public class GrammarCatalogController {
    private final PublishedGrammarCatalogs catalogs;private final AppUserProvisioningService users;
    public GrammarCatalogController(PublishedGrammarCatalogs catalogs,AppUserProvisioningService users) {this.catalogs=catalogs;this.users=users;}
    public record Start(@Min(1) @Max(50) Integer limit,@Size(max = 50) List<@NotNull UUID> itemIds) {}
    private UUID learner(Jwt jwt) {return users.provision(jwt.getSubject(),jwt.getClaimAsString("email")).id();}

    @GetMapping("/catalogs/{kind}")
    public List<PublishedGrammarCatalogs.Catalog> list(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String kind,
        @RequestParam(required = false) UUID parentId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
    ) {
        return catalogs.list(learner(jwt), kind, parentId, page, size);
    }

    @GetMapping("/catalogs/{kind}/page")
    public CatalogPage<PublishedGrammarCatalogs.Catalog> page(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String kind,
        @RequestParam(required = false) UUID parentId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
    ) {
        return catalogs.page(learner(jwt), kind, parentId, page, size);
    }

    @GetMapping("/catalogs/{kind}/{id}")
    public PublishedGrammarCatalogs.Catalog detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id) {
        return catalogs.detail(learner(jwt),kind,id);
    }

    @PostMapping("/catalogs/{kind}/{id}/sessions")
    public ResponseEntity<AssessmentSessions.View> start(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String kind,
        @PathVariable UUID id,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody(required = false) Start input
    ) {
        Start effective = input == null ? new Start(10, null) : input;
        int limit = effective.limit() == null ? 10 : effective.limit();
        var session = (idempotencyKey == null || idempotencyKey.isBlank())
            ? catalogs.start(learner(jwt), kind, id, limit, effective.itemIds())
            : catalogs.start(learner(jwt), kind, id, limit, effective.itemIds(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/assessment-sessions/" + session.id())).body(session);
    }
}
