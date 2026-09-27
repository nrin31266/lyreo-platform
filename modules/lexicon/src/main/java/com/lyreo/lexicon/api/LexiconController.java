package com.lyreo.lexicon.api;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconEntry;
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
    public List<LexiconEntryResponse> search(@RequestParam("q") String query,
                                             @RequestParam(defaultValue = "20") int limit) {
        return search.search(query, limit).stream().map(LexiconEntryResponse::from).toList();
    }

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
                                String definitionEn, String translationVi, String translationStatus) {
        static SenseResponse from(LexiconEntry.LexiconSense sense) {
            return new SenseResponse(sense.id(), sense.itemId(), sense.position(), sense.partOfSpeech(),
                sense.definitionEn(), sense.translationVi(), sense.translationStatus().name());
        }
    }

    public record PronunciationResponse(UUID id, UUID itemId, String accent, String ipa,
                                        String audioFile, String audioUrl, String sourceUrl,
                                        String cachedAudioObjectKey) {
        static PronunciationResponse from(LexiconEntry.Pronunciation p) {
            return new PronunciationResponse(p.id(), p.itemId(), p.accent(), p.ipa(), p.audioFile(),
                p.audioUrl(), p.sourceUrl(), p.cachedAudioObjectKey());
        }
    }
}
