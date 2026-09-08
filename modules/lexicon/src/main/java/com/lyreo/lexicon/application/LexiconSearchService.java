package com.lyreo.lexicon.application;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;

public class LexiconSearchService {
    private final LexiconRepository repository;

    public LexiconSearchService(LexiconRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = "lexiconHotEntries", key = "#rawQuery + ':' + #limit")
    public List<LexiconEntry> search(String rawQuery, int limit) {
        String query = rawQuery == null ? "" : rawQuery.strip().toLowerCase();
        if (query.length() < 1) return List.of();
        return repository.search(query, Math.min(Math.max(limit, 1), 50));
    }
}
