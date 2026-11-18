package com.lyreo.toeic.api;
import com.lyreo.toeic.application.ToeicEditor;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/admin/toeic")
public class ToeicExchangeController {
    private final ToeicEditor editor;private final EditorialFiles files;
    public ToeicExchangeController(ToeicEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public Object importFile(@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean apply,@AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        var preview=files.preview(file.getBytes(),file.getOriginalFilename(),row->{
            ToeicDraft content;
            content=files.decode(row,ToeicDraft.class);
            editor.validateShape(content);editor.validateStructure(content);return content;
        });
        if(!apply) return preview;if(preview.invalid()>0) throw new RequestValidationException("Fix every row before importing drafts");
        return preview.rows().stream().map(row->editor.save(UUID.randomUUID(),0,row.content(),jwt.getSubject())).toList();
    }
    @GetMapping("/{id}/export") public ResponseEntity<String> export(@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=toeic.json").body(files.json(List.of(editor.get(id).draft())));
    }
}
