package com.lyreo.toeic.api;
import com.lyreo.toeic.application.ToeicEditor;
import com.lyreo.toeic.application.port.ToeicEditorSource;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/admin/toeic")
public class ToeicAdminController {
    private final ToeicEditor editor;private final ToeicEditorSource source;private final DocumentStore documents;
    public ToeicAdminController(ToeicEditor editor,ToeicEditorSource source,DocumentStore documents) {this.editor=editor;this.source=source;this.documents=documents;}
    public record Save(long revision,ToeicDraft content) {}
    public record Action(long revision,String reason,String status,UUID publicationId) {}
    @GetMapping("/source-review") public List<ToeicEditorSource.ReviewTarget> reviewTargets() {return source.reviewTargets();}
    @GetMapping("/page") public com.lyreo.platform.content.CatalogPage<ToeicEditorSource.Summary> page(@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String access,@RequestParam(defaultValue="") String quality,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {
        var items=list(q,year,status,type,access,quality,page,size);
        return new com.lyreo.platform.content.CatalogPage<>(items,source.count(q,year,status,type,access,quality),page,size);
    }
    @GetMapping("/{id}/audit") public List<Map<String,Object>> audit(@PathVariable UUID id) {return documents.audit("toeic",id);}
    @GetMapping public List<ToeicEditorSource.Summary> list(@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String access,@RequestParam(defaultValue="") String quality,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {if(page<0 || page>100000 || size<1 || size>100) throw new com.lyreo.contracts.errors.RequestValidationException("Invalid page");return source.list(q,year,status,type,access,quality,page,size);}
    @GetMapping("/{id}") public DocumentStore.Document<ToeicDraft> get(@PathVariable UUID id) {return editor.get(id);}
    @GetMapping("/{id}/preview") public ToeicEditor.Preview preview(@PathVariable UUID id) {return editor.preview(id);}
    @PutMapping("/{id}/draft") public DocumentStore.Document<ToeicDraft> save(@PathVariable UUID id,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return editor.save(id,input.revision(),input.content(),jwt.getSubject());}
    @GetMapping("/{id}/source") public ToeicDraft source(@PathVariable UUID id) {return source.find(id).orElseThrow(()->new com.lyreo.contracts.errors.ResourceNotFoundException("No dataset source for this test"));}
    @GetMapping("/{id}/readiness") public List<ToeicEditor.Issue> readiness(@PathVariable UUID id) {return editor.readiness(editor.get(id).draft());}
    @PostMapping("/{id}/publish") public DocumentStore.Document<ToeicDraft> publish(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return editor.publish(id,a.revision(),a.reason(),jwt.getSubject());}
    @GetMapping("/{id}/history") public List<DocumentStore.Publication<ToeicDraft>> history(@PathVariable UUID id) {return documents.history("toeic",id,ToeicDraft.class);}
    @PostMapping("/{id}/restore") public DocumentStore.Document<ToeicDraft> restore(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return editor.restore(id,a.revision(),a.publicationId(),jwt.getSubject());}
    @org.springframework.transaction.annotation.Transactional @PutMapping("/{id}/status") public DocumentStore.Document<ToeicDraft> status(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {
        var current=editor.get(id);if(current.revision()!=a.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; reload first");
        if(current.revision()==0) current=documents.save("toeic",current.id(),0,current.draft(),jwt.getSubject(),ToeicDraft.class);
        documents.status("toeic",current.id(),current.revision(),a.status(),jwt.getSubject(),ToeicDraft.class,source.metadata(current.id()).isPresent());return editor.get(current.id());
    }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@RequestParam long revision,@AuthenticationPrincipal Jwt jwt) {documents.deleteDraft("toeic",id,revision,jwt.getSubject());}

    public record Bulk(List<DocumentStore.Target> items,String status) {}
    @PostMapping("/bulk-preview") public List<Map<String,Object>> preview(@RequestBody Bulk input) {
        return documents.bulk(input.items(),target->{var current=editor.get(target.id());return Map.of("id",current.id(),"revision",current.revision(),"title",Objects.toString(current.draft().title(),""),"status",current.status(),"usage",documents.usage(current.id()));});
    }
    @PostMapping("/bulk-status") public List<DocumentStore.Document<ToeicDraft>> bulkStatus(@RequestBody Bulk input,@AuthenticationPrincipal Jwt jwt) {
        return documents.bulk(input.items(),target->{
            var current=editor.get(target.id());
            if(current.revision()!=target.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; preview this selection again");
            if(current.revision()==0) current=documents.save("toeic",current.id(),0,current.draft(),jwt.getSubject(),ToeicDraft.class);
            documents.status("toeic",current.id(),current.revision(),input.status(),jwt.getSubject(),ToeicDraft.class,source.metadata(current.id()).isPresent());return editor.get(current.id());
        });
    }

}
