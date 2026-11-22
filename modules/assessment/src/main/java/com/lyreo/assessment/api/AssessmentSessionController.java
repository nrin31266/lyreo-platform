package com.lyreo.assessment.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.platform.content.AssetStore;
import java.util.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assessment-sessions")
public class AssessmentSessionController {
    private final AssessmentSessions sessions;private final AppUserProvisioningService users;private final AssetStore assets;
    public AssessmentSessionController(AssessmentSessions sessions,AppUserProvisioningService users,AssetStore assets) {this.sessions=sessions;this.users=users;this.assets=assets;}
    public record Answers(long revision,Map<UUID,String> answers) {}
    public record Submit(long revision) {}
    private UUID learner(Jwt jwt) {return users.provision(jwt.getSubject(),jwt.getClaimAsString("email")).id();}
    @GetMapping public List<AssessmentSessions.History> history(@AuthenticationPrincipal Jwt jwt,@RequestParam String domain,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return sessions.history(learner(jwt),domain,page,size);}
    @GetMapping("/{id}") public AssessmentSessions.View get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {return sessions.get(learner(jwt),id);}
    @PutMapping("/{id}/answers") public AssessmentSessions.View save(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Answers input) {return sessions.save(learner(jwt),id,input.revision(),input.answers());}
    @PostMapping("/{id}/submit") public AssessmentSessions.View submit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Submit input) {return sessions.submit(learner(jwt),id,input.revision());}
    @GetMapping("/{id}/media/{assetId}") public ResponseEntity<org.springframework.core.io.Resource> media(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID assetId) {
        var asset=sessions.media(learner(jwt),id,assetId);var bytes=assets.bytes(asset.id());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(asset.mimeType())).cacheControl(CacheControl.noStore()).contentLength(bytes.length).header("X-Content-Type-Options","nosniff").body(new ByteArrayResource(bytes));
    }
}
