package com.lyreo.learner.domain;

public record LearnerPreferences(
    String preferredAccent,
    DisplayTiming translation,
    DisplayTiming sentenceIpa,
    DisplayTiming vocabularyNotes,
    DisplayTiming grammarNotes,
    boolean thoughtGroups,
    boolean karaokeHighlighting,
    boolean properNounHints,
    double defaultPlaybackSpeed
) {
    public enum DisplayTiming {
        OFF, TAP_TO_SHOW, AFTER_ATTEMPT, ALWAYS
    }

    public static LearnerPreferences defaults() {
        return new LearnerPreferences(
            "US",
            DisplayTiming.AFTER_ATTEMPT,
            DisplayTiming.TAP_TO_SHOW,
            DisplayTiming.AFTER_ATTEMPT,
            DisplayTiming.AFTER_ATTEMPT,
            true,
            true,
            true,
            1.0
        );
    }
}
