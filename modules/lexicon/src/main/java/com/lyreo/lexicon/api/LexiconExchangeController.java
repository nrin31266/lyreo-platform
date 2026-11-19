package com.lyreo.lexicon.api;

import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lexicon")
public class LexiconExchangeController {
    private final LexiconEditor editor;private final EditorialFiles files;
    public LexiconExchangeController(LexiconEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
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
