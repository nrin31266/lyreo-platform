package com.lyreo.assessment.api;
import com.lyreo.assessment.application.QuestionAi;
import com.lyreo.platform.content.AiDraftRequests;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
@org.springframework.modulith.NamedInterface(value="api",propagate=false)
public class AssessmentEditorial {
    private final QuestionAi ai;private final AiDraftRequests requests;
    public AssessmentEditorial(QuestionAi ai,AiDraftRequests requests) {this.ai=ai;this.requests=requests;}
    public AiDraftRequests.Request suggest(UUID id,long revision,String brief,String actor,Object context) {return ai.request(id,revision,"SUGGEST",20,brief,actor,context);}
    public List<AiDraftRequests.Request> history(UUID id) {return requests.history("assessment",id);}
    public AiDraftRequests.Request request(UUID id) {return requests.get(id,false);}
    public com.lyreo.platform.content.DocumentStore.Document<com.lyreo.assessment.domain.QuestionContent> accept(UUID id,UUID request,long revision,String kind,int index,String actor) {return ai.accept(id,request,revision,kind,index,actor).document();}
}
