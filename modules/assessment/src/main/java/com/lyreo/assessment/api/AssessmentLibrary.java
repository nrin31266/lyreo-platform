package com.lyreo.assessment.api;
import com.lyreo.assessment.application.port.QuestionSource;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.platform.content.DocumentStore.Document;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.modulith.NamedInterface;

@Service
@NamedInterface(value="api",propagate=false)
public class AssessmentLibrary {
    private final DocumentStore documents; private final QuestionSource source;
    public AssessmentLibrary(DocumentStore documents,QuestionSource source) {this.documents=documents;this.source=source;}
    public Document<QuestionContent> get(UUID id) {
        var row=documents.find("assessment",id,QuestionContent.class);
        if(row.isPresent() && documents.sourceSelected("assessment",row.get().id())) {
            var current=row.get();
            return new Document<>(current.id(),current.revision(),"PUBLISHED",null,source.current(current.id()).orElseThrow(()->new ResourceNotFoundException("Source question unavailable")),current.updatedAt(),current.updatedBy(),false);
        }
        if(row.isPresent() && row.get().publicationId()==null) return source.current(row.get().id()).map(raw->documents.againstSource(row.get(),raw,"PUBLISHED")).orElse(row.get());
        return row.orElseGet(()->documents.source(id,source.find(id).orElseThrow(()->new ResourceNotFoundException("Question not found"))));
    }
    public QuestionContent published(UUID id,UUID publicationId) {
        if(publicationId!=null) return documents.publication("assessment",id,publicationId,QuestionContent.class).content();
        // A source ID already identifies an immutable release snapshot, including after rollback.
        return source.find(id).orElseGet(()->documents.published("assessment",id,QuestionContent.class)
            .orElseThrow(()->new ResourceNotFoundException("Question is not published")).content());
    }
    public UUID pin(UUID id,UUID publicationId) {
        if(id==null) throw new RequestValidationException("Question ID is required");
        if(publicationId!=null) {published(id,publicationId);return publicationId;}
        if(source.find(id).isPresent()) return null;
        return documents.published("assessment",id,QuestionContent.class)
            .orElseThrow(()->new ResourceNotFoundException("Question is not published")).id();
    }
    public void validateStandalone(UUID id,UUID publicationId) {
        var question=published(id,publicationId);validate(question,true);
        require(!"CLOZE".equals(question.kind()) && !source.requiresContext(id),"This question needs its TOEIC passage/audio group and cannot be added to a standalone Grammar bank");
        require(question.title()!=null && !question.title().isBlank() && question.options().stream().noneMatch(o->o.text().isBlank()),"Standalone Grammar questions require a stem and nonempty options");
    }
    public List<Map<String,Object>> context(UUID id) {return source.context(id);}
    public Document<QuestionContent> save(UUID id,long revision,QuestionContent data,String actor) {validate(data,false);documents.save("assessment",id,revision,data,actor,QuestionContent.class);return get(id);}
    public Document<QuestionContent> create(QuestionContent data,String actor) {return save(UUID.randomUUID(),0,data,actor);}
    public Document<QuestionContent> publish(UUID id,long revision,String reason,String actor) {validate(get(id).draft(),true);return documents.publish("assessment",id,revision,reason,actor,QuestionContent.class);}
    public static void validate(QuestionContent q,boolean publish) {
        require(q!=null && q.options()!=null && q.media()!=null,"Question options and media are required");
        require(q.kind()!=null && q.kind().length()<=64,"Question kind is required");
        require(q.title()==null || q.title().length()<=30000,"Question text is too long");
        require(q.difficultyLevel()==null || q.difficultyLevel()>=1 && q.difficultyLevel()<=5,"Difficulty must be 1–5");
        require(q.options().size()<=4 && q.media().size()<=10,"Too many options or media");
        var keys=new HashSet<String>();for(var o:q.options()) require(o!=null && o.key()!=null && Set.of("A","B","C","D").contains(o.key()) && keys.add(o.key()) && o.text()!=null && o.text().length()<=30000,"Options need unique A–D keys and text");
        for(var m:q.media()) require(m!=null && m.assetId()!=null && m.role()!=null && Set.of("audio","image").contains(m.role()),"Invalid media reference");
        if(publish) {require(keys.size()>=2,"At least two options are required");require(keys.contains(q.correctOption()),"Correct answer must be one of the options");require("CLOZE".equals(q.kind()) || q.title()!=null && !q.title().isBlank() || q.transcriptEn()!=null && !q.transcriptEn().isBlank() || !q.media().isEmpty(),"Question needs text, transcript or media");}
    }
    private static void require(boolean value,String message) {if(!value) throw new RequestValidationException(message);}
}
