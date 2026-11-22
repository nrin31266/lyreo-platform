package com.lyreo.grammar.api;
import com.lyreo.grammar.application.GrammarCatalogEditor;
import com.lyreo.platform.content.EditorialFiles;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/grammar")
public class GrammarExchangeController {
    private final GrammarCatalogEditor editor;private final EditorialFiles files;
    public GrammarExchangeController(GrammarCatalogEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
    @GetMapping("/{kind}/{id}/export") public ResponseEntity<String> export(@PathVariable String kind,@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=grammar.json").body(files.json(List.of(editor.get(kind,id).draft())));
    }
}
