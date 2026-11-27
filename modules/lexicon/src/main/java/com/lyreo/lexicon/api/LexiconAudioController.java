package com.lyreo.lexicon.api;

import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.ByteRangeResponses;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/lexicon")
public class LexiconAudioController {
    private final LexiconSearchService search;private final AssetStore assets;
    public LexiconAudioController(LexiconSearchService search,AssetStore assets) {this.search=search;this.assets=assets;}
    @GetMapping("/{id}/pronunciations/{pronunciationId}/audio")
    public ResponseEntity<Resource> audio(
        @PathVariable UUID id,
        @PathVariable UUID pronunciationId,
        @RequestHeader(value = "Range", required = false) String rangeHeader
    ) {
        var entry=search.findById(id).orElseThrow(()->new ResourceNotFoundException("Published entry not found"));
        var sound=entry.pronunciations().stream().filter(p->p.id().equals(pronunciationId) && p.assetId()!=null)
            .findFirst().orElseThrow(()->new ResourceNotFoundException("Published recording not found"));
        var asset=assets.get(sound.assetId());var bytes=assets.bytes(asset.id());
        return ByteRangeResponses.stream(asset, bytes, rangeHeader);
    }
}
