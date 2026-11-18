package com.lyreo.lexicon.api;

import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.lexicon.domain.LexiconDraft;
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
@RequestMapping("/api/v1/admin/lexicon")
public class LexiconExchangeController {
    private final LexiconEditor editor;private final EditorialFiles files;
    public LexiconExchangeController(LexiconEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public Object importFile(@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean apply,@AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        var identities=new HashSet<String>();
        var preview=files.preview(file.getBytes(),file.getOriginalFilename(),row->{
            var content=decode(row);editor.validateNew(content);
            if(!identities.add(content.title()+"\0"+content.type())) throw new RequestValidationException("Duplicate headword and type in this file");return content;
        });
        if(!apply) return preview;
        if(preview.invalid()>0) throw new RequestValidationException("Fix every row before importing drafts");
        return preview.rows().stream().map(row->editor.create(row.content(),jwt.getSubject())).toList();
    }
    private LexiconDraft decode(Map<String,Object> row) {
        if(row.containsKey("items")) return files.decode(row,LexiconDraft.class);
        UUID item=UUID.randomUUID(),sense=UUID.randomUUID();String translation=EditorialFiles.text(row,"translation");String example=EditorialFiles.text(row,"example");
        return new LexiconDraft(EditorialFiles.text(row,"headword"),Objects.toString(row.get("type"),"WORD"),null,null,null,
            List.of(new LexiconDraft.Item(item,EditorialFiles.text(row,"pos"),null,null)),
            List.of(new LexiconDraft.Sense(sense,item,EditorialFiles.text(row,"definition"),translation.isBlank()?null:translation,translation.isBlank()?"MISSING":"NEEDS_REVIEW",List.of(),example.isBlank()?List.of():List.of(Map.of("text",example)),List.of(),null)),List.of(),List.of(),List.of());
    }
    @GetMapping("/{id}/export") public ResponseEntity<String> export(@PathVariable UUID id,@RequestParam(defaultValue="json") String format) {
        var data=editor.get(id).draft();String body;
        if(format.equals("csv")) {
            if(data.senses().size()!=1) throw new RequestValidationException("Use versioned JSON to preserve entries with multiple senses");
            var sense=data.senses().getFirst();String pos=data.items().stream().filter(item->item.id().equals(sense.itemId())).findFirst().orElseThrow().partOfSpeech();
            body=files.csv(List.of("headword","type","pos","definition","translation","example"),List.of(Arrays.asList(data.title(),data.type(),pos,sense.definitionEn(),sense.translationVi(),sense.examples().isEmpty()?"":Objects.toString(sense.examples().getFirst().get("text"),""))));
        } else {if(!format.equals("json")) throw new RequestValidationException("Choose CSV or JSON");body=files.json(List.of(data));}
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.equals("csv")?"text/csv;charset=UTF-8":"application/json"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=lexicon."+format).body(body);
    }
}
