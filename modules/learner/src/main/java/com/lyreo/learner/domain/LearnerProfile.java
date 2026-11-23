package com.lyreo.learner.domain;

import java.time.Instant;
import java.util.UUID;

public record LearnerProfile(
    UUID learnerId,
    String displayName,
    String currentLevel,
    String goal,
    Integer dailyMinutes,
    String focusArea,
    LearnerPreferences preferences,
    Instant onboardingCompletedAt,
    String avatarPreset,
    UUID avatarUploadId
) {
    public enum Goal { CONVERSATION, WORK, TOEIC, UNDECIDED }
    public enum Level { A1, A2, B1, B2, C1, C2, UNKNOWN }
    public enum FocusArea { LISTENING, SPEAKING, VOCABULARY, GRAMMAR }

    public boolean onboarded() {
        return onboardingCompletedAt != null;
    }

    public StarterSuggestion starterSuggestion() {
        Goal topic = switch (goal) {
            case "WORK" -> Goal.WORK;
            case "TOEIC" -> Goal.TOEIC;
            case null, default -> Goal.CONVERSATION;
        };
        StarterSuggestion.Difficulty difficulty = switch (currentLevel) {
            case "B1", "B2" -> StarterSuggestion.Difficulty.INTERMEDIATE;
            case "C1", "C2" -> StarterSuggestion.Difficulty.ADVANCED;
            case null, default -> StarterSuggestion.Difficulty.BEGINNER;
        };
        return new StarterSuggestion(topic, difficulty, dailyMinutes);
    }

    public record StarterSuggestion(Goal topic, Difficulty difficulty, Integer dailyMinutes) {
        public enum Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
    }

    public record Patch(
        Appearance appearance,
        Goal goal, Level currentLevel, Integer dailyMinutes,
        boolean changeFocusArea, FocusArea focusArea
    ) {}

    public record Appearance(boolean changeName, String name, boolean changeAvatar, String avatarPreset) {}
}
