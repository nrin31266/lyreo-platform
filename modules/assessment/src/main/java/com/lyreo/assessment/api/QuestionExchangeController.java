package com.lyreo.assessment.api;

import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/assessment-items")
public class QuestionExchangeController {
    private final AssessmentLibrary library;private final EditorialFiles files;
    public QuestionExchangeController(AssessmentLibrary library,EditorialFiles files) {this.library=library;this.files=files;}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public Object importFile(@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean apply,@AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        var preview=files.preview(file.getBytes(),file.getOriginalFilename(),row->{
            QuestionContent content;
            if(row.containsKey("options")) content=files.decode(row,QuestionContent.class);
            else {
                var options=new ArrayList<QuestionContent.Option>();for(String key:List.of("A","B","C","D")) if(!EditorialFiles.text(row,"option"+key).isBlank()) options.add(new QuestionContent.Option(key,EditorialFiles.text(row,"option"+key)));
                String difficulty=EditorialFiles.text(row,"difficultyLevel");
                content=new QuestionContent(EditorialFiles.text(row,"title"),Objects.toString(row.get("kind"),"MCQ_SINGLE"),options,EditorialFiles.text(row,"correctOption"),difficulty.isBlank()?null:Integer.valueOf(difficulty),EditorialFiles.text(row,"explanationVi"),EditorialFiles.text(row,"translationVi"),null,null,EditorialFiles.text(row,"transcriptEn"),List.of());
            }
            AssessmentLibrary.validate(content,false);return content;
        });
        if(!apply) return preview;if(preview.invalid()>0) throw new RequestValidationException("Fix every row before importing drafts");
        return preview.rows().stream().map(row->library.create(row.content(),jwt.getSubject())).toList();
    }
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
