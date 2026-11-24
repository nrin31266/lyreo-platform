package com.lyreo.vocabulary.api;

import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyCatalog.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/vocabulary")
public class VocabularyCatalogController {
    private final VocabularyCatalog catalog;

    public VocabularyCatalogController(VocabularyCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/packs")
    public List<Pack> packs() {
        return catalog.packs();
    }

    @GetMapping("/packs/{id}/units")
    public List<Unit> units(@PathVariable UUID id) {
        return catalog.units(id);
    }

    @GetMapping("/entries")
    public EntryPage entries(
        @RequestParam(required = false) UUID packId,
        @RequestParam(required = false) UUID unitId,
        @RequestParam(defaultValue = "") @Size(max = 120) String q,
        @RequestParam(defaultValue = "ALL") Review review,
        @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page
    ) {
        return catalog.entries(packId, unitId, q.strip(), review, page);
    }

    @GetMapping("/entries/{id}")
    public Entry entry(@PathVariable UUID id) {
        return catalog.entry(id);
    }

    @PutMapping("/packs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void savePack(@PathVariable UUID id, @Valid @RequestBody PackEdit edit) {
        catalog.savePack(id, edit);
    }

    @PutMapping("/units/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveUnit(@PathVariable UUID id, @Valid @RequestBody UnitEdit edit) {
        catalog.saveUnit(id, edit);
    }

    @PutMapping("/entries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveEntry(@PathVariable UUID id, @Valid @RequestBody EntryEdit edit) {
        catalog.saveEntry(id, edit);
    }
}
