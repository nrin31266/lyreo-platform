package com.lyreo.grammar.api;
import com.lyreo.grammar.application.GrammarCatalogEditor;
import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/admin/grammar")
public class GrammarAdminController {
    private final GrammarCatalogEditor editor;private final GrammarCatalogSource source;private final DocumentStore documents;
    public GrammarAdminController(GrammarCatalogEditor editor,GrammarCatalogSource source,DocumentStore documents) {this.editor=editor;this.source=source;this.documents=documents;}
    @GetMapping("/review") public List<GrammarCatalogEditor.ReviewIssue> review() {return editor.review();}
    @GetMapping("/{kind}/{id}/check") public List<GrammarCatalogEditor.CheckIssue> check(@PathVariable String kind,@PathVariable UUID id) {return editor.check(kind,id);}
    public record Save(long revision,GrammarCatalog content) {}
    public record Action(long revision,String reason,String status,UUID publicationId) {}
    @GetMapping("/{kind}/page") public com.lyreo.platform.content.CatalogPage<GrammarCatalogSource.Summary> page(@PathVariable String kind,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID parentId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String access,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {
        var items=list(kind,q,parentId,status,access,page,size);
        return new com.lyreo.platform.content.CatalogPage<>(items,source.count(kind,q,parentId,status,access),page,size);
    }
    @GetMapping("/{kind}/{id}/audit") public List<Map<String,Object>> audit(@PathVariable String kind,@PathVariable UUID id) {return documents.audit(editor.namespace(kind),id);}
    @GetMapping("/{kind}") public List<GrammarCatalogSource.Summary> list(@PathVariable String kind,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID parentId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String access,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {editor.namespace(kind);if(page<0 || page>100000 || size<1 || size>100) throw new com.lyreo.contracts.errors.RequestValidationException("Invalid page");return source.list(kind,q,parentId,status,access,page,size);}
    @GetMapping("/{kind}/{id}") public DocumentStore.Document<GrammarCatalog> get(@PathVariable String kind,@PathVariable UUID id) {return editor.get(kind,id);}
    @PostMapping("/{kind}") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public DocumentStore.Document<GrammarCatalog> create(@PathVariable String kind,@RequestBody GrammarCatalog data,@AuthenticationPrincipal Jwt jwt) {return editor.save(kind,UUID.randomUUID(),0,data,jwt.getSubject());}
    @PutMapping("/{kind}/{id}/draft") public DocumentStore.Document<GrammarCatalog> save(@PathVariable String kind,@PathVariable UUID id,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return editor.save(kind,id,input.revision(),input.content(),jwt.getSubject());}
    @PostMapping("/{kind}/{id}/publish") public DocumentStore.Document<GrammarCatalog> publish(@PathVariable String kind,@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return editor.publish(kind,id,a.revision(),a.reason(),jwt.getSubject());}
    @GetMapping("/{kind}/{id}/history") public List<DocumentStore.Publication<GrammarCatalog>> history(@PathVariable String kind,@PathVariable UUID id) {return documents.history(editor.namespace(kind),id,GrammarCatalog.class);}
    @PostMapping("/{kind}/{id}/restore") public DocumentStore.Document<GrammarCatalog> restore(@PathVariable String kind,@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return editor.restore(kind,id,a.revision(),a.publicationId(),jwt.getSubject());}
    @org.springframework.transaction.annotation.Transactional @PutMapping("/{kind}/{id}/status") public DocumentStore.Document<GrammarCatalog> status(@PathVariable String kind,@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {
        var current=editor.get(kind,id);if(current.revision()!=a.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; reload first");
        if(current.revision()==0) current=documents.save(editor.namespace(kind),current.id(),0,current.draft(),jwt.getSubject(),GrammarCatalog.class);
        documents.status(editor.namespace(kind),current.id(),current.revision(),a.status(),jwt.getSubject(),GrammarCatalog.class,source.metadata(kind,current.id()).isPresent());return editor.get(kind,current.id());
    }
    @DeleteMapping("/{kind}/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String kind,@PathVariable UUID id,@RequestParam long revision,@AuthenticationPrincipal Jwt jwt) {documents.deleteDraft(editor.namespace(kind),id,revision,jwt.getSubject());}

    public record Bulk(List<DocumentStore.Target> items,String status) {}
    @PostMapping("/{kind}/bulk-preview") public List<Map<String,Object>> preview(@PathVariable String kind,@RequestBody Bulk input) {
        return documents.bulk(input.items(),target->{var current=editor.get(kind,target.id());return Map.of("id",current.id(),"revision",current.revision(),"title",Objects.toString(current.draft().title(),""),"status",current.status(),"usage",documents.usage(current.id()));});
    }
    @PostMapping("/{kind}/bulk-status") public List<DocumentStore.Document<GrammarCatalog>> bulkStatus(@PathVariable String kind,@RequestBody Bulk input,@AuthenticationPrincipal Jwt jwt) {
        return documents.bulk(input.items(),target->{
            var current=editor.get(kind,target.id());
            if(current.revision()!=target.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; preview this selection again");
            if(current.revision()==0) current=documents.save(editor.namespace(kind),current.id(),0,current.draft(),jwt.getSubject(),GrammarCatalog.class);
            documents.status(editor.namespace(kind),current.id(),current.revision(),input.status(),jwt.getSubject(),GrammarCatalog.class,source.metadata(kind,current.id()).isPresent());return editor.get(kind,current.id());
        });
    }

}
