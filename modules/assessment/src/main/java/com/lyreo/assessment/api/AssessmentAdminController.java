package com.lyreo.assessment.api;
import com.lyreo.assessment.application.port.QuestionSource;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/assessment-items")
public class AssessmentAdminController {
    private final AssessmentLibrary library;private final QuestionSource source;private final DocumentStore documents;
    public AssessmentAdminController(AssessmentLibrary library,QuestionSource source,DocumentStore documents) {this.library=library;this.source=source;this.documents=documents;}
    public record Save(long revision,QuestionContent content) {}
    public record Action(long revision,String reason,String status,UUID publicationId) {}
    @GetMapping("/page") public com.lyreo.platform.content.CatalogPage<com.lyreo.assessment.application.port.QuestionSource.Summary> page(@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer difficulty,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {
        var items=list(q,difficulty,status,page,size);
        return new com.lyreo.platform.content.CatalogPage<>(items,source.count(q,difficulty,status),page,size);
    }
    @GetMapping("/{id}/audit") public List<Map<String,Object>> audit(@PathVariable UUID id) {return documents.audit("assessment",id);}
    @GetMapping public List<QuestionSource.Summary> list(@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer difficulty,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {
        if(page<0 || page>100000 || size<1 || size>100) throw new com.lyreo.contracts.errors.RequestValidationException("Invalid page");return source.list(q,difficulty,status,page,size);
    }
    @GetMapping("/{id}") public DocumentStore.Document<QuestionContent> get(@PathVariable UUID id) {return library.get(id);}
    @GetMapping("/{id}/usage") public List<Map<String,Object>> usage(@PathVariable UUID id) {return source.usage(id);}
    @PutMapping("/{id}/draft") public DocumentStore.Document<QuestionContent> save(@PathVariable UUID id,@RequestBody Save input,@AuthenticationPrincipal Jwt jwt) {return library.save(id,input.revision(),input.content(),jwt.getSubject());}
    @PostMapping("/{id}/publish") public DocumentStore.Document<QuestionContent> publish(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return library.publish(id,a.revision(),a.reason(),jwt.getSubject());}
    @GetMapping("/{id}/history") public List<DocumentStore.Publication<QuestionContent>> history(@PathVariable UUID id) {return documents.history("assessment",id,QuestionContent.class);}
    @PostMapping("/{id}/restore") public DocumentStore.Document<QuestionContent> restore(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {return documents.restore("assessment",id,a.revision(),a.publicationId(),jwt.getSubject(),QuestionContent.class);}
    @org.springframework.transaction.annotation.Transactional @PutMapping("/{id}/status") public DocumentStore.Document<QuestionContent> status(@PathVariable UUID id,@RequestBody Action a,@AuthenticationPrincipal Jwt jwt) {
        var current=library.get(id);if(current.revision()!=a.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; reload first");
        if(current.revision()==0) current=documents.save("assessment",current.id(),0,current.draft(),jwt.getSubject(),QuestionContent.class);
        documents.status("assessment",current.id(),current.revision(),a.status(),jwt.getSubject(),QuestionContent.class,source.find(current.id()).isPresent());return library.get(current.id());
    }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id,@RequestParam long revision,@AuthenticationPrincipal Jwt jwt) {documents.deleteDraft("assessment",id,revision,jwt.getSubject());}

    public record Bulk(List<DocumentStore.Target> items,String status) {}
    @PostMapping("/bulk-preview") public List<Map<String,Object>> preview(@RequestBody Bulk input) {
        return documents.bulk(input.items(),target->{var current=library.get(target.id());return Map.of("id",current.id(),"revision",current.revision(),"title",Objects.toString(current.draft().title(),""),"status",current.status(),"usage",documents.usage(current.id()));});
    }
    @PostMapping("/bulk-status") public List<DocumentStore.Document<QuestionContent>> bulkStatus(@RequestBody Bulk input,@AuthenticationPrincipal Jwt jwt) {
        return documents.bulk(input.items(),target->{
            var current=library.get(target.id());
            if(current.revision()!=target.revision()) throw new com.lyreo.contracts.errors.StateConflictException("Content changed; preview this selection again");
            if(current.revision()==0) current=documents.save("assessment",current.id(),0,current.draft(),jwt.getSubject(),QuestionContent.class);
            documents.status("assessment",current.id(),current.revision(),input.status(),jwt.getSubject(),QuestionContent.class,source.find(current.id()).isPresent());return library.get(current.id());
        });
    }

}
