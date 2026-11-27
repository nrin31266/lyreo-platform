package com.lyreo.vocabulary.domain;

import java.util.List;

/** Supported practice exercise modes for curated vocabulary units and packs. */
public enum PracticeMode {
    CHOOSE_MEANING,
    CHOOSE_WORD,
    LETTER_SCRAMBLE,
    MISSING_LETTERS,
    LISTEN_WRITE,
    CONTEXT_CLOZE,
    GUESS_WORD,
    MIXED;

    public static final List<PracticeMode> STANDARD_PRACTICE_MODES = List.of(
        CHOOSE_MEANING,
        CHOOSE_WORD,
        LETTER_SCRAMBLE,
        MISSING_LETTERS,
        LISTEN_WRITE,
        CONTEXT_CLOZE
    );
}
