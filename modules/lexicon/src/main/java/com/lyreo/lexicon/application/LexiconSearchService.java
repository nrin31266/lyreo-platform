package com.lyreo.lexicon.application;

import com.lyreo.lexicon.application.port.LexiconRepository;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import java.util.Optional;
import java.util.UUID;

public class LexiconSearchService {
    private final LexiconRepository repository;

    public LexiconSearchService(LexiconRepository repository) {
        this.repository = repository;
    }

    public LexiconSearchResult search(String rawQuery, int limit) {
        String displayQuery = LexiconLookupNormalizer.displayQuery(rawQuery);
        if (displayQuery.isEmpty()) return LexiconSearchResult.empty();
        return repository.search(displayQuery, LexiconLookupNormalizer.lookupQuery(displayQuery),
            Math.min(Math.max(limit, 1), 50));
    }

    public Optional<LexiconEntry> findById(UUID id) {
        return repository.findById(id);
    }
}
