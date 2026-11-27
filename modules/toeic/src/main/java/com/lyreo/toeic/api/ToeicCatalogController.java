package com.lyreo.toeic.api;

import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.CatalogPage;
import com.lyreo.toeic.application.PublishedToeicTests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/toeic/catalog")
public class ToeicCatalogController {
    private final PublishedToeicTests tests;private final AppUserProvisioningService users;
    public ToeicCatalogController(PublishedToeicTests tests,AppUserProvisioningService users) {this.tests=tests;this.users=users;}
    public record Start(
        @NotBlank @Pattern(regexp = "FULL_TEST|DRILL") String mode,
        @Size(max = 200) List<@NotNull UUID> placementIds,
        @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> parts
    ) {
        public Start(String mode,List<UUID> placementIds) {this(mode,placementIds,null);}
    }
    private UUID learner(Jwt jwt) {return users.provision(jwt.getSubject(),jwt.getClaimAsString("email")).id();}
    @GetMapping public List<PublishedToeicTests.Catalog> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return tests.list(learner(jwt),page,size);}
    @GetMapping("/page") public CatalogPage<PublishedToeicTests.Catalog> page(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return tests.page(learner(jwt),page,size);}
    @GetMapping("/{id}") public PublishedToeicTests.Catalog detail(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return tests.detail(learner(jwt),id);}
    @PostMapping("/{id}/sessions")
    public ResponseEntity<AssessmentSessions.View> start(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,
        @Valid @RequestBody Start input
    ) {
        boolean hasParts = input.parts() != null;
        boolean hasIdempotency = idempotencyKey != null && !idempotencyKey.isBlank();
        var session = (!hasParts && !hasIdempotency)
            ? tests.start(learner(jwt),id,input.mode(),input.placementIds())
            : tests.start(learner(jwt),id,input.mode(),input.placementIds(),input.parts(),idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/assessment-sessions/"+session.id())).body(session);
    }
}
