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

/** Global learner dictionary API. Contextual lesson notes are separate lesson annotations. */
@RestController
@RequestMapping("/api/v1/lexicon")
public class LexiconController {
    private final LexiconSearchService search;

    public LexiconController(LexiconSearchService search) {
        this.search = search;
    }

    @GetMapping("/search")
    public List<LexiconEntryResponse> search(@RequestParam("q") String query, @RequestParam(defaultValue = "20") int limit) {
        return search.search(query, limit).stream()
            .map(LexiconEntryResponse::from)
            .toList();
    }

    @GetMapping("/{id}")
    public LexiconEntryResponse get(@PathVariable UUID id) {
        return search.findById(id)
            .map(LexiconEntryResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("Lexicon entry not found: " + id));
    }

    public record LexiconEntryResponse(
        UUID id,
        String canonicalForm,
        String normalizedForm,
        String type,
        String language,
        List<SenseResponse> senses,
        List<PronunciationResponse> pronunciations
    ) {
        public static LexiconEntryResponse from(LexiconEntry entry) {
            if (entry == null) return null;
            return new LexiconEntryResponse(
                entry.id(),
                entry.canonicalForm(),
                entry.normalizedForm(),
                entry.type() != null ? entry.type().name() : null,
                entry.language(),
                entry.senses() == null ? List.of() : entry.senses().stream().map(SenseResponse::from).toList(),
                entry.pronunciations() == null ? List.of() : entry.pronunciations().stream().map(PronunciationResponse::from).toList()
            );
        }

        public record SenseResponse(
            UUID id,
            String partOfSpeech,
            String definitionEn,
            String translationVi,
            String translationStatus,
            UUID sourceId
        ) {
            public static SenseResponse from(LexiconEntry.LexiconSense sense) {
                return new SenseResponse(
                    sense.id(),
                    sense.partOfSpeech(),
                    sense.definitionEn(),
                    sense.translationVi(),
                    sense.translationStatus() != null ? sense.translationStatus().name() : null,
                    sense.sourceId()
                );
            }
        }

        public record PronunciationResponse(
            String accent,
            String ipa,
            String externalAudioUrl,
            String cachedAudioObjectKey,
            UUID sourceId
        ) {
            public static PronunciationResponse from(LexiconEntry.Pronunciation p) {
                return new PronunciationResponse(
                    p.accent(),
                    p.ipa(),
                    p.externalAudioUrl(),
                    p.cachedAudioObjectKey(),
                    p.sourceId()
                );
            }
        }
    }
}
