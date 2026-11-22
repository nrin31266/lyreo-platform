package com.lyreo.assessment.api;

import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/assessment-items")
public class QuestionExchangeController {
    private final AssessmentLibrary library;private final EditorialFiles files;
    public QuestionExchangeController(AssessmentLibrary library,EditorialFiles files) {this.library=library;this.files=files;}
    @GetMapping("/{id}/export") public ResponseEntity<String> export(@PathVariable UUID id,@RequestParam(defaultValue="json") String format) {
        var q=library.get(id).draft();String body;
        if(format.equals("csv")) {
            if(!q.media().isEmpty() || q.answerTranslationVi()!=null || q.vocabularyNote()!=null) throw new RequestValidationException("Use versioned JSON to preserve media and all annotations");
            var values=new ArrayList<>(Arrays.asList(q.title(),q.kind(),q.correctOption(),Objects.toString(q.difficultyLevel(),""),q.translationVi(),q.explanationVi(),q.transcriptEn()));
            for(String key:List.of("A","B","C","D")) values.add(q.options().stream().filter(o->o.key().equals(key)).map(QuestionContent.Option::text).findFirst().orElse(""));
            body=files.csv(List.of("title","kind","correctOption","difficultyLevel","translationVi","explanationVi","transcriptEn","optionA","optionB","optionC","optionD"),List.of(values));
        } else {if(!format.equals("json")) throw new RequestValidationException("Choose CSV or JSON");body=files.json(List.of(q));}
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("csv")?"text/csv;charset=UTF-8":"application/json"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=questions."+format).body(body);
    }
    @GetMapping("/{id}/preview") public QuestionContent preview(@PathVariable UUID id,@RequestParam(required=false) UUID publicationId) {return library.published(id,publicationId);}
}
