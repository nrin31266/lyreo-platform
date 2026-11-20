package com.lyreo.lexicon.api;

import com.lyreo.lexicon.application.*;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.platform.content.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lexicon/{id}/ai")
public class LexiconAiController {
    private final LexiconAi ai;private final LexiconEditor editor;private final AiDraftRequests requests;
    public LexiconAiController(LexiconAi ai,LexiconEditor editor,AiDraftRequests requests) {this.ai=ai;this.editor=editor;this.requests=requests;}
    public record Request(long revision,List<UUID> senseIds,String brief,String operation,String voice,Boolean reviewed,UUID itemId) {}
    public record Accept(long revision,int index,String mode) {}
    @GetMapping public List<AiDraftRequests.Request> history(@PathVariable UUID id) {return requests.history("lexicon",editor.get(id).id());}
    @PostMapping public ResponseEntity<AiDraftRequests.Request> request(@PathVariable UUID id,@RequestBody Request input,@AuthenticationPrincipal Jwt jwt) {
        var result="TTS".equals(input.operation())?ai.requestTts(id,input.revision(),input.voice(),Boolean.TRUE.equals(input.reviewed()),input.itemId(),jwt.getSubject()):ai.request(id,input.revision(),input.senseIds(),input.brief(),input.operation(),jwt.getSubject());
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+result.jobId())).body(result);
    }
    @PostMapping("/{requestId}/accept") public DocumentStore.Document<LexiconDraft> accept(@PathVariable UUID id,@PathVariable UUID requestId,@RequestBody Accept input,@AuthenticationPrincipal Jwt jwt) {
        return ai.accept(id,requestId,input.revision(),input.index(),input.mode(),jwt.getSubject());
    }
}
