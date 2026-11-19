package com.lyreo.grammar.api;

import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.grammar.application.PublishedGrammarCatalogs;
import com.lyreo.identity.application.AppUserProvisioningService;
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
    @GetMapping("/catalogs/{kind}") public List<PublishedGrammarCatalogs.Catalog> list(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {return catalogs.list(learner(jwt),kind,page,size);}
    @GetMapping("/catalogs/{kind}/{id}") public PublishedGrammarCatalogs.Catalog detail(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id) {return catalogs.detail(learner(jwt),kind,id);}
    @PostMapping("/catalogs/{kind}/{id}/sessions") public ResponseEntity<AssessmentSessions.View> start(@AuthenticationPrincipal Jwt jwt,@PathVariable String kind,@PathVariable UUID id,@RequestBody Start input) {var session=catalogs.start(learner(jwt),kind,id,input.limit()==null?10:input.limit(),input.itemIds());return ResponseEntity.created(URI.create("/api/v1/assessment-sessions/"+session.id())).body(session);}

}
