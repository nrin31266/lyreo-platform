package com.lyreo.lexicon.api;
import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lexicon")
public class LexiconAdminController {
    private final LexiconEditor editor; private final DocumentStore documents;
    public LexiconAdminController(LexiconEditor editor,DocumentStore documents) {this.editor=editor;this.documents=documents;}
    public record Save(long revision,LexiconDraft content) {}
    public record Action(long revision,String reason,String status,UUID publicationId) {}
    @GetMapping("/count") public long count(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String translation,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String pos) {return editor.count(q,type,translation,state,pos);}
    @GetMapping("/browse") public List<com.lyreo.lexicon.application.port.LexiconEditorSource.Summary> browse(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String translation,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String pos,@RequestParam(required=false) String after,@RequestParam(required=false) String before,@RequestParam(defaultValue="false") boolean tail,@RequestParam(defaultValue="0") int skip,@RequestParam(defaultValue="30") int size) {return editor.browse(q,type,translation,state,pos,after,before,tail,skip,size);}
    @GetMapping("/{id}/audit") public List<Map<String,Object>> audit(@PathVariable UUID id) {return documents.audit("lexicon",id);}
    @GetMapping public List<com.lyreo.lexicon.application.port.LexiconEditorSource.Summary> list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String translation,@RequestParam(defaultValue="") String state,@RequestParam(defaultValue="") String pos,@RequestParam(required=false) String after,@RequestParam(defaultValue="30") int size) {return editor.list(q,type,translation,state,pos,after,size);}
    @GetMapping("/{id}") public DocumentStore.Document<LexiconDraft> get(@PathVariable UUID id) {return editor.get(id);}
    @PutMapping("/{id}/draft") public DocumentStore.Document<LexiconDraft> save(@PathVariable UUID id,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return editor.save(id,input.revision(),input.content(),jwt.getSubject());}
    @PostMapping("/{id}/publish") public DocumentStore.Document<LexiconDraft> publish(@PathVariable UUID id,@RequestBody Action action,@AuthenticationPrincipal Jwt jwt) {return editor.publish(id,action.revision(),action.reason(),jwt.getSubject());}
    @org.springframework.transaction.annotation.Transactional @PutMapping("/{id}/status") public DocumentStore.Document<LexiconDraft> status(@PathVariable UUID id,@RequestBody Action action,@AuthenticationPrincipal Jwt jwt) {
        var current=editor.get(id);if(current.revision()!=action.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; reload first");
        if(current.revision()==0) current=documents.save("lexicon",current.id(),0,current.draft(),jwt.getSubject(),LexiconDraft.class);
        documents.status("lexicon",current.id(),current.revision(),action.status(),jwt.getSubject(),LexiconDraft.class,editor.sourcePublished(current.id()));return editor.get(current.id());
    }
    @GetMapping("/{id}/history") public List<DocumentStore.Publication<LexiconDraft>> history(@PathVariable UUID id) {return documents.history("lexicon",id,LexiconDraft.class);}
    @PostMapping("/{id}/restore") public DocumentStore.Document<LexiconDraft> restore(@PathVariable UUID id,@RequestBody Action action,@AuthenticationPrincipal Jwt jwt) {return editor.restore(id,action.revision(),action.publicationId(),jwt.getSubject());}
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@RequestParam long revision,@AuthenticationPrincipal Jwt jwt) {documents.deleteDraft("lexicon",id,revision,jwt.getSubject());}

    public record Bulk(List<DocumentStore.Target> items,String status) {}
    @PostMapping("/bulk-preview") public List<Map<String,Object>> preview(@RequestBody Bulk input) {
        return documents.bulk(input.items(),target->{var current=editor.get(target.id());return Map.of("id",current.id(),"revision",current.revision(),"title",Objects.toString(current.draft().title(),""),"status",current.status(),"usage",documents.usage(current.id()));});
    }
    @PostMapping("/bulk-status") public List<DocumentStore.Document<LexiconDraft>> bulkStatus(@RequestBody Bulk input,@AuthenticationPrincipal Jwt jwt) {
        return documents.bulk(input.items(),target->{
            var current=editor.get(target.id());
            if(current.revision()!=target.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; preview this selection again");
            if(current.revision()==0) current=documents.save("lexicon",current.id(),0,current.draft(),jwt.getSubject(),LexiconDraft.class);
            documents.status("lexicon",current.id(),current.revision(),input.status(),jwt.getSubject(),LexiconDraft.class,editor.sourcePublished(current.id()));return editor.get(current.id());
        });
    }

}
