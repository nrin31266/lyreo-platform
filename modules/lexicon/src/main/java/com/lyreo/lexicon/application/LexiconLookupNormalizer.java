package com.lyreo.lexicon.application;

import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Normalizer2;

/** Mirrors the clean release builder's NFKC, strip, full casefold lookup contract. */
final class LexiconLookupNormalizer {
    private static final Normalizer2 NFKC = Normalizer2.getNFKCInstance();

    private LexiconLookupNormalizer() {}

    static String displayQuery(String rawQuery) {
        String normalized = NFKC.normalize(rawQuery == null ? "" : rawQuery);
        int start = 0;
        int end = normalized.length();
        while (start < end && isBuilderWhitespace(normalized.codePointAt(start))) {
            start += Character.charCount(normalized.codePointAt(start));
        }
        while (end > start && isBuilderWhitespace(normalized.codePointBefore(end))) {
            end -= Character.charCount(normalized.codePointBefore(end));
        }
        return normalized.substring(start, end);
    }

    private static boolean isBuilderWhitespace(int codePoint) {
        // Python str.strip includes Unicode White_Space plus these four separators.
        return UCharacter.isUWhiteSpace(codePoint) || (codePoint >= 0x1c && codePoint <= 0x1f);
    }

    static String lookupQuery(String displayQuery) {
        return UCharacter.foldCase(displayQuery, true);
    }
}
