package com.lyreo.toeic.api;
import com.lyreo.toeic.application.ToeicEditor;
import com.lyreo.platform.content.EditorialFiles;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/admin/toeic")
public class ToeicExchangeController {
    private final ToeicEditor editor;private final EditorialFiles files;
    public ToeicExchangeController(ToeicEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
    @GetMapping("/{id}/export") public ResponseEntity<String> export(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=toeic.json").body(files.json(List.of(editor.get(id).draft())));
    }
}
