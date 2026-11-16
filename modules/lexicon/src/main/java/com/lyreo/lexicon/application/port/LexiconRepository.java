package com.lyreo.lexicon.application.port;

import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface LexiconRepository {
    Optional<LexiconEntry> findById(UUID id);
    LexiconSearchResult search(String displayQuery, String lookupQuery, int limit);
    Optional<LexiconEntry> findByNormalizedForm(String normalizedForm);

    /** At most two distinct identities suffice to distinguish a unique exact match from ambiguity. */
    List<UUID> exactHeadwordIds(String normalizedForm);
}
