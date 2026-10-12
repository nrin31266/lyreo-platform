package com.lyreo.lexicon.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global dictionary over the active Lexicon release. */
@RestController
@RequestMapping("/api/v1/lexicon")
public class LexiconController {
    private final LexiconSearchService search;

    public LexiconController(LexiconSearchService search) { this.search = search; }

    @GetMapping("/search")
    public LexiconSearchResponse search(@RequestParam("q") String query,
                                        @RequestParam(defaultValue = "20") int limit) {
        LexiconSearchResult result = search.search(query, limit);
        return new LexiconSearchResponse(result.results().stream()
            .map(hit -> new LexiconSearchHitResponse(hit.entryId(), hit.headword(),
                hit.entryType().name(), hit.partsOfSpeech(), hit.matchType().name(),
                hit.matchedText())).toList(), result.hasMore(), result.sourceMetadataJson(),
            result.licenseText());
    }

    public record LexiconSearchResponse(List<LexiconSearchHitResponse> results, boolean hasMore,
                                        String sourceMetadataJson, String licenseText) {}

    public record LexiconSearchHitResponse(UUID entryId, String headword, String entryType,
                                           List<String> partsOfSpeech, String matchType,
                                           String matchedText) {}

    @GetMapping("/{id}")
    public LexiconEntryResponse get(@PathVariable UUID id) {
        return search.findById(id).map(LexiconEntryResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("Lexicon entry not found: " + id));
    }

    public record LexiconEntryResponse(
        UUID id, UUID headwordId, String canonicalForm, String normalizedForm,
        String type, String language, String sourceMetadataJson, String licenseText,
        List<LexiconEntry.LexiconItem> items,
        List<LexiconEntry.LexiconForm> forms, List<SenseResponse> senses,
        List<PronunciationResponse> pronunciations,
        List<LexiconEntry.LexiconTranslation> translations
    ) {
        public static LexiconEntryResponse from(LexiconEntry entry) {
            return new LexiconEntryResponse(entry.id(), entry.headwordId(), entry.canonicalForm(),
                entry.normalizedForm(), entry.type().name(), entry.language(),
                entry.sourceMetadataJson(), entry.licenseText(), entry.items(), entry.forms(),
                entry.senses().stream().map(SenseResponse::from).toList(),
                entry.pronunciations().stream().map(PronunciationResponse::from).toList(),
                entry.translations());
        }
    }

    public record SenseResponse(UUID id, UUID itemId, int position, String partOfSpeech,
                                String definitionEn, String translationVi, String translationStatus,
                                String rawGlossesJson, String examplesJson, String tagsJson,
                                String matchedQualifier) {
        static SenseResponse from(LexiconEntry.LexiconSense sense) {
            return new SenseResponse(sense.id(), sense.itemId(), sense.position(), sense.partOfSpeech(),
                sense.definitionEn(), sense.translationVi(), sense.translationStatus().name(),
                sense.rawGlossesJson(), sense.examplesJson(), sense.tagsJson(), sense.matchedQualifier());
        }
    }

    public record PronunciationResponse(UUID id, UUID itemId, String accent, String ipa,
                                        String audioFile, String audioUrl, String sourceUrl,
                                        String tagsJson) {
        static PronunciationResponse from(LexiconEntry.Pronunciation p) {
            return new PronunciationResponse(p.id(), p.itemId(), p.accent(), p.ipa(), p.audioFile(),
                p.audioUrl(), p.sourceUrl(), p.tagsJson());
        }
    }
}
