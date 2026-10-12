package com.lyreo.lexicon.domain;

import java.util.List;
import java.util.UUID;

/** A bounded search page over one active Lexicon release. */
public record LexiconSearchResult(
    List<Hit> results,
    boolean hasMore,
    String sourceMetadataJson,
    String licenseText
) {
    public enum MatchType { EXACT_HEADWORD, EXACT_FORM, PREFIX_HEADWORD, PREFIX_FORM }

    public record Hit(
        UUID entryId,
        String headword,
        LexiconEntry.EntryType entryType,
        List<String> partsOfSpeech,
        MatchType matchType,
        String matchedText
    ) {}

    public static LexiconSearchResult empty() {
        return new LexiconSearchResult(List.of(), false, null, null);
    }
}
