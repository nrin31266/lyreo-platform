package com.lyreo.toeic.api;
import com.lyreo.toeic.application.ToeicQuestionEditor;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.*;
import java.util.*;
import java.net.URI;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/admin/toeic/{id}/questions/{placement}")
public class ToeicQuestionController {
    private final ToeicQuestionEditor editor;
    public ToeicQuestionController(ToeicQuestionEditor editor) {this.editor=editor;}
    public record Save(long testRevision,long revision,QuestionContent content) {}
    public record Suggest(long testRevision,long revision,String operation,String brief) {}
    public record Accept(long testRevision,long revision,String kind,int index) {}
    @GetMapping public DocumentStore.Document<QuestionContent> get(@PathVariable UUID id,@PathVariable UUID placement) {return editor.get(id,placement);}
    @PutMapping("/draft") public DocumentStore.Document<QuestionContent> draft(@PathVariable UUID id,@PathVariable UUID placement,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return editor.draft(id,placement,input.testRevision(),input.revision(),input.content(),jwt.getSubject());}
    @PutMapping public DocumentStore.Document<ToeicDraft> save(@PathVariable UUID id,@PathVariable UUID placement,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return editor.save(id,placement,input.testRevision(),input.revision(),input.content(),jwt.getSubject());}
    @GetMapping("/ai") public List<AiDraftRequests.Request> history(@PathVariable UUID id,@PathVariable UUID placement) {return editor.history(id,placement);}
    @PostMapping("/ai") public ResponseEntity<AiDraftRequests.Request> suggest(@PathVariable UUID id,@PathVariable UUID placement,@RequestBody Suggest input,@AuthenticationPrincipal Jwt jwt) {
        if(!"SUGGEST".equals(input.operation())) throw new com.lyreo.contracts.errors.RequestValidationException("Full TOEIC tests support field suggestions only");
        var request=editor.suggest(id,placement,input.testRevision(),input.revision(),input.brief()==null?"":input.brief(),jwt.getSubject());
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/"+request.jobId())).body(request);
    }
    @PostMapping("/ai/{request}/accept") public DocumentStore.Document<QuestionContent> accept(@PathVariable UUID id,@PathVariable UUID placement,@PathVariable UUID request,@RequestBody Accept input,@AuthenticationPrincipal Jwt jwt) {return editor.accept(id,placement,request,input.testRevision(),input.revision(),input.kind(),input.index(),jwt.getSubject());}
}
