package com.lyreo.lexicon.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
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
    private final LexiconDetailMapper detailMapper;

    public LexiconController(LexiconSearchService search, ObjectMapper objectMapper) {
        this.search = search;
        this.detailMapper = new LexiconDetailMapper(objectMapper);
    }

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
        return search.findById(id).map(detailMapper::from)
            .orElseThrow(() -> new ResourceNotFoundException("Lexicon entry not found: " + id));
    }

    public record LexiconEntryResponse(
        UUID id, UUID headwordId, String canonicalForm, String normalizedForm,
        String type, String language, String sourceMetadataJson, String licenseText,
        List<LexiconEntry.LexiconItem> items,
        List<FormResponse> forms, List<SenseResponse> senses,
        List<PronunciationResponse> pronunciations,
        List<TranslationResponse> translations
    ) {}

    public record FormResponse(UUID id, UUID itemId, String form, String normalizedForm,
                               List<String> tags) {}

    public record SenseResponse(UUID id, UUID itemId, int position, String partOfSpeech,
                                String definitionEn, String translationVi, String translationStatus,
                                List<String> rawGlosses, List<Map<String, Object>> examples,
                                List<String> tags, String matchedQualifier) {}

    public record PronunciationResponse(UUID id, UUID itemId, String accent, String ipa,
                                        String audioFile, String audioUrl, String sourceUrl,
                                        List<String> tags) {}

    public record TranslationResponse(UUID id, UUID itemId, UUID senseId, String wordVi,
                                      String source, String sourceScope, String sourceSenseQualifier,
                                      String linkStatus, String unlinkedReason, List<String> tags, String sourcePos) {}
}
