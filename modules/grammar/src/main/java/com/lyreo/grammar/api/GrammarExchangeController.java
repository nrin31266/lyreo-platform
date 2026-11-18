package com.lyreo.grammar.api;
import com.lyreo.grammar.application.GrammarCatalogEditor;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.platform.content.EditorialFiles;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/v1/admin/grammar")
public class GrammarExchangeController {
    private final GrammarCatalogEditor editor;private final EditorialFiles files;
    public GrammarExchangeController(GrammarCatalogEditor editor,EditorialFiles files) {this.editor=editor;this.files=files;}
    @PostMapping(value="/{kind}/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional
    public Object importFile(@PathVariable String kind,@RequestParam MultipartFile file,@RequestParam(defaultValue="false") boolean apply,@AuthenticationPrincipal Jwt jwt) throws java.io.IOException {
        var preview=files.preview(file.getBytes(),file.getOriginalFilename(),row->{
            GrammarCatalog content;
            if(row.containsKey("questions")) content=files.decode(row,GrammarCatalog.class);
            else {
                String parent=EditorialFiles.text(row,"parentId"),order=EditorialFiles.text(row,"orderIndex");
                String access=Objects.toString(row.get("accessMode"),"PUBLIC");
                content=new GrammarCatalog(EditorialFiles.text(row,"title"),EditorialFiles.text(row,"titleEn"),EditorialFiles.text(row,"description"),EditorialFiles.text(row,"descriptionEn"),parent.isBlank()?null:UUID.fromString(parent),order.isBlank()?0:Integer.parseInt(order),access,access.equals("FEATURE")?"grammar.advanced":null,List.of());
            }
            editor.validate(kind,content,false);return content;
        });
        if(!apply) return preview;if(preview.invalid()>0) throw new RequestValidationException("Fix every row before importing drafts");
        return preview.rows().stream().map(row->editor.save(kind,UUID.randomUUID(),0,row.content(),jwt.getSubject())).toList();
    }
    @GetMapping("/{kind}/{id}/export") public ResponseEntity<String> export(@PathVariable String kind,@PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=grammar.json").body(files.json(List.of(editor.get(kind,id).draft())));
    }
}
