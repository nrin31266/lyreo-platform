package com.lyreo.assessment.api;

import com.lyreo.assessment.application.QuestionAi;
import com.lyreo.platform.content.AiDraftRequests;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/assessment-items/{id}/ai")
public class QuestionAiController {
    private final QuestionAi ai;private final AiDraftRequests requests;private final AssessmentLibrary library;
    public QuestionAiController(QuestionAi ai,AiDraftRequests requests,AssessmentLibrary library) {this.ai=ai;this.requests=requests;this.library=library;}
    public record Request(long revision,String operation,Integer count,String brief,String voice,Boolean reviewed) {}
    public record Accept(long revision,String kind,int index) {}
    @GetMapping public List<AiDraftRequests.Request> history(@PathVariable UUID id) {return requests.history("assessment",library.get(id).id());}
    @PostMapping public ResponseEntity<AiDraftRequests.Request> request(@PathVariable UUID id,@RequestBody Request input,@AuthenticationPrincipal Jwt jwt) {
        if(!"TTS".equals(input.operation()) && input.count()==null) throw new com.lyreo.contracts.errors.RequestValidationException("Suggestion count is required");
        var result="TTS".equals(input.operation())?ai.requestTts(id,input.revision(),input.voice(),Boolean.TRUE.equals(input.reviewed()),jwt.getSubject()):ai.request(id,input.revision(),input.operation(),input.count(),input.brief(),jwt.getSubject());
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+result.jobId())).body(result);
    }
    @PostMapping("/{requestId}/accept") public QuestionAi.Accepted accept(@PathVariable UUID id,@PathVariable UUID requestId,@RequestBody Accept input,@AuthenticationPrincipal Jwt jwt) {
        return ai.accept(id,requestId,input.revision(),input.kind(),input.index(),jwt.getSubject());
    }
}
