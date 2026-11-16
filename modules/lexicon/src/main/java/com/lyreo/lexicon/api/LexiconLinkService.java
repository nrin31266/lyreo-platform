package com.lyreo.lexicon.api;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Normalizer2;
import com.lyreo.lexicon.application.port.LexiconRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Exact contextual linking never chooses arbitrarily among distinct dictionary identities. */
@NamedInterface(value = "api", propagate = false)
public final class LexiconLinkService {
    private final LexiconRepository repository;
    public LexiconLinkService(LexiconRepository repository) { this.repository = repository; }

    public Optional<UUID> resolve(String form) {
        if (form == null || form.isBlank()) return Optional.empty();
        String normalized = UCharacter.foldCase(Normalizer2.getNFKCInstance().normalize(form).strip(), true);
        var candidates = repository.exactHeadwordIds(normalized);
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }
}
