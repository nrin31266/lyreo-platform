package com.lyreo.lexicon.application;

import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LexiconRepository {
    Optional<LexiconEntry> findById(UUID id);
    List<LexiconEntry> search(String query, int limit);
    Optional<LexiconEntry> findByNormalizedForm(String normalizedForm);
}
