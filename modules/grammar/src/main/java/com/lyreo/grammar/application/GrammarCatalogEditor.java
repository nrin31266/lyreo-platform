package com.lyreo.grammar.application;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.platform.content.DocumentStore.Document;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrammarCatalogEditor {
    private final DocumentStore documents;private final GrammarCatalogSource source;private final AssessmentLibrary items;
    public GrammarCatalogEditor(DocumentStore documents,GrammarCatalogSource source,AssessmentLibrary items) {this.documents=documents;this.source=source;this.items=items;}
    public String namespace(String kind) {if(!Set.of("topic","subtopic","bank").contains(kind)) throw new RequestValidationException("Unknown Grammar catalog kind");return "grammar-"+kind;}
    public Document<GrammarCatalog> get(String kind,UUID id) {
        if(documents.sourceSelected(namespace(kind),id)) {
            var row=documents.find(namespace(kind),id,GrammarCatalog.class).orElseThrow();
            var raw=source.find(kind,id).orElseThrow(()->new ResourceNotFoundException("Source content unavailable"));
            return new Document<>(id,row.revision(),source.status(kind,id),null,raw,row.updatedAt(),row.updatedBy(),false);
        }
        var row=documents.find(namespace(kind),id,GrammarCatalog.class);
        if(row.isPresent() && row.get().publicationId()==null) return source.find(kind,id).map(raw->documents.againstSource(row.get(),raw,source.status(kind,id))).orElse(row.get());
        return row.orElseGet(()->{
        var data=source.find(kind,id).orElseThrow(()->new ResourceNotFoundException("Grammar catalog not found"));
        return new Document<>(id,0,source.status(kind,id),null,data,null,null);
    });}
    public Document<GrammarCatalog> save(String kind,UUID id,long revision,GrammarCatalog data,String actor) {validate(kind,data,false);get(kind,id);documents.save(namespace(kind),id,revision,data,actor,GrammarCatalog.class);return get(kind,id);}
    @Transactional public Document<GrammarCatalog> publish(String kind,UUID id,long revision,String reason,String actor) {
        var data=get(kind,id).draft();validate(kind,data,true);
        for(var ref:data.questions()) items.validateStandalone(ref.itemId(),ref.publicationId());
        var pinned=data.questions().stream().map(ref->new GrammarCatalog.QuestionRef(ref.itemId(),items.pin(ref.itemId(),ref.publicationId()))).toList();
        if(!pinned.equals(data.questions())) {
            var content=new GrammarCatalog(data.title(),data.titleEn(),data.description(),data.descriptionEn(),data.parentId(),data.orderIndex(),data.accessMode(),data.requiredFeatureKey(),pinned);
            revision=documents.save(namespace(kind),id,revision,content,actor,GrammarCatalog.class).revision();
        }
        return documents.publish(namespace(kind),id,revision,reason,actor,GrammarCatalog.class);
    }
    @Transactional public Document<GrammarCatalog> restore(String kind,UUID id,long revision,UUID publicationId,String actor) {
        var content=documents.publication(namespace(kind),id,publicationId,GrammarCatalog.class).content();
        validate(kind,content,true);
        for(var ref:content.questions()) items.validateStandalone(ref.itemId(),ref.publicationId());
        return documents.restore(namespace(kind),id,revision,publicationId,actor,GrammarCatalog.class);
    }
    public record ReviewIssue(UUID id,String title,String kind,String code,UUID parentId,UUID questionId,String severity,String message) {
        public ReviewIssue(UUID id,String title,String kind,String code,UUID parentId) {this(id,title,kind,code,parentId,null,"ERROR",code);}
    }
    public List<ReviewIssue> review() {return review(()->{});}
    public List<ReviewIssue> review(Runnable checkpoint) {
        var topics=summaries("topic");
        var parents=new HashMap<UUID,GrammarCatalogSource.Summary>();topics.forEach(t->parents.put(t.id(),t));
        var issues=new ArrayList<ReviewIssue>();
        for(var sub:summaries("subtopic")) {
            var parent=parents.get(sub.parentId());
            if(parent==null) issues.add(new ReviewIssue(sub.id(),sub.title(),"subtopic","MISSING_PARENT",sub.parentId()));
            else if(sub.status().equals("PUBLISHED") && !parent.status().equals("PUBLISHED")) issues.add(new ReviewIssue(sub.id(),sub.title(),"subtopic","HIDDEN_PARENT",sub.parentId()));
        }
        for(var bank:summaries("bank")) if(bank.questionCount()==0 && !Set.of("HIDDEN","ARCHIVED").contains(bank.status())) issues.add(new ReviewIssue(bank.id(),bank.title(),"bank","EMPTY_BANK",null));
        for(String kind:List.of("topic","subtopic","bank")) for(var catalog:summaries(kind)) {
            checkpoint.run();
            for(var issue:check(kind,catalog.id(),checkpoint)) {
                if(issue.code().equals("EMPTY_BANK")) continue;
                issues.add(new ReviewIssue(catalog.id(),catalog.title(),kind,issue.code(),catalog.parentId(),issue.questionId(),issue.severity(),issue.message()));
            }
        }
        return issues;
    }
    private List<GrammarCatalogSource.Summary> summaries(String kind) {
        var result=new ArrayList<GrammarCatalogSource.Summary>();
        for(int page=0;;page++) {var rows=source.list(kind,"",null,"","",page,100);result.addAll(rows);if(rows.size()<100) return result;}
    }
    public record CheckIssue(String code,UUID questionId,int number,String severity,String message) {
        public CheckIssue(String code,UUID questionId,int number) {this(code,questionId,number,"ERROR",code);}
    }
    public List<CheckIssue> check(String kind,UUID id) {return check(kind,id,()->{});}
    public List<CheckIssue> check(String kind,UUID id,Runnable checkpoint) {
        var current=get(kind,id);var result=new ArrayList<CheckIssue>();var data=current.draft();
        try {validate(kind,data,true);} catch(RequestValidationException|ResourceNotFoundException invalid) {result.add(new CheckIssue(kind.equals("bank") && data.questions().isEmpty()?"EMPTY_BANK":"INVALID_CATALOG",null,0,"ERROR",invalid.getMessage()));}
        for(int i=0;i<data.questions().size();i++) {
            checkpoint.run();
            var ref=data.questions().get(i);
            try {
                items.validateStandalone(ref.itemId(),ref.publicationId());
            } catch(RequestValidationException|ResourceNotFoundException invalid) {result.add(new CheckIssue("INVALID_QUESTION",ref.itemId(),i+1,"ERROR",invalid.getMessage()));}
        }
        return result;
    }
    public void validate(String kind,GrammarCatalog d,boolean publish) {
        namespace(kind);
        if(d==null || d.title()==null || d.title().isBlank() || d.title().length()>500 || d.questions()==null) throw new RequestValidationException("Title and question collection are required");
        if(d.accessMode()==null || !Set.of("PUBLIC","FEATURE").contains(d.accessMode()) || "FEATURE".equals(d.accessMode()) && !"grammar.advanced".equals(d.requiredFeatureKey()) || "PUBLIC".equals(d.accessMode()) && d.requiredFeatureKey()!=null) throw new RequestValidationException("Invalid Grammar access policy");
        if(kind.equals("subtopic")) {if(d.parentId()==null) throw new RequestValidationException("Parent topic is required");get("topic",d.parentId());}
        if(d.questions().size()>5000) throw new RequestValidationException("At most 5000 questions per catalog");
        var unique=new HashSet<UUID>();for(var ref:d.questions()) if(ref==null || ref.itemId()==null || !unique.add(ref.itemId())) throw new RequestValidationException("Question references must be unique");
        if(publish && kind.equals("bank") && d.questions().isEmpty()) throw new RequestValidationException("Add questions before publishing a bank");
    }

}
