package com.lyreo.assessment.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.ByteRangeResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/assessment-sessions")
public class AssessmentSessionController {
    private final AssessmentSessions sessions;private final AppUserProvisioningService users;private final AssetStore assets;
    public AssessmentSessionController(AssessmentSessions sessions,AppUserProvisioningService users,AssetStore assets) {this.sessions=sessions;this.users=users;this.assets=assets;}
    public record Answers(@NotNull @Min(0) Long revision,@NotNull @Size(max = 200) Map<UUID,String> answers) {}
    public record Submit(@NotNull @Min(0) Long revision) {}
    private UUID learner(Jwt jwt) {return users.provision(jwt.getSubject(),jwt.getClaimAsString("email")).id();}
    @GetMapping public AssessmentSessions.HistoryPage history(@AuthenticationPrincipal Jwt jwt,@RequestParam String domain,@RequestParam(required=false) String status,@RequestParam(required=false) UUID catalogId,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {return sessions.historyPage(learner(jwt),domain,status,catalogId,page,size);}
    @GetMapping("/{id}") public AssessmentSessions.View get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return sessions.get(learner(jwt),id);}
    @PutMapping("/{id}/answers") public AssessmentSessions.View save(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Answers input) {return sessions.save(learner(jwt),id,input.revision(),input.answers());}
    @PostMapping("/{id}/submit") public AssessmentSessions.View submit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@Valid @RequestBody Submit input) {return sessions.submit(learner(jwt),id,input.revision());}
    @GetMapping("/{id}/media/{assetId}") public ResponseEntity<Resource> media(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID assetId,@RequestHeader(value = "Range", required = false) String rangeHeader) {
        var asset=sessions.media(learner(jwt),id,assetId);var bytes=assets.bytes(asset.id());
        return ByteRangeResponses.stream(asset,bytes,rangeHeader);
    }
}
