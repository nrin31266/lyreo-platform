package com.lyreo.grammar.api;

import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.grammar.application.PublishedGrammarCatalogs;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.CatalogPage;
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
    public record Start(Integer limit,List<UUID> itemIds) {}
    private UUID learner(Jwt jwt) {return users.provision(jwt.getSubject(),jwt.getClaimAsString("email")).id();}

    @GetMapping("/catalogs/{kind}")
    public List<PublishedGrammarCatalogs.Catalog> list(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String kind,
        @RequestParam(required = false) UUID parentId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size
    ) {
        return catalogs.list(learner(jwt), kind, parentId, page, size);
    }

    @GetMapping("/catalogs/{kind}/page")
    public CatalogPage<PublishedGrammarCatalogs.Catalog> page(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String kind,
        @RequestParam(required = false) UUID parentId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "30") int size
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
        @RequestBody Start input
    ) {
        int limit = input.limit() == null ? 10 : input.limit();
        var session = (idempotencyKey == null || idempotencyKey.isBlank())
            ? catalogs.start(learner(jwt), kind, id, limit, input.itemIds())
            : catalogs.start(learner(jwt), kind, id, limit, input.itemIds(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/assessment-sessions/" + session.id())).body(session);
    }
}
