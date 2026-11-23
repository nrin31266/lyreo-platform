package com.lyreo.lesson.domain.authoring;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CaptionCueText {
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\[\\]]+)\\]|\\(([^()]+)\\)");
    private static final Pattern MARKS = Pattern.compile("[♪♫♬\\u200B\\u200E\\u200F\\u202A-\\u202E]");
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    // shortcut: only explicit, common cue labels; keep unknown bracketed words for review.
    private static final Set<String> SOUND_CUES = Set.of(
        "applause", "audience clapping", "clapping", "cheering", "audience cheering", "laughter", "laughs", "laughing",
        "sighs", "gasps", "coughs", "inaudible", "silence", "music", "background music", "instrumental music",
        "music playing", "plane roaring", "plane roars", "airplane roaring", "engine roaring", "engines roaring"
    );

    private CaptionCueText() {}

    public static String speech(String text) {
        String plain = WHITESPACE.matcher(MARKS.matcher(text).replaceAll("")).replaceAll(" ");
        String speech = BRACKETED.matcher(plain).replaceAll(cue -> {
            String label = cue.group(1) != null ? cue.group(1) : cue.group(2);
            return SOUND_CUES.contains(label.strip().toLowerCase(Locale.ROOT)) ? " " : Matcher.quoteReplacement(cue.group());
        });
        return WHITESPACE.matcher(speech).replaceAll(" ").strip();
    }

    public static boolean isNonSpeech(String text) {
        return !text.isBlank() && speech(text).isEmpty();
    }
}
