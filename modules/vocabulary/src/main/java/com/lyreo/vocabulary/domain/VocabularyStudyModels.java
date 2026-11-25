package com.lyreo.vocabulary.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Domain types for vocabulary study catalog, 6-mode sessions, and progress. */
public final class VocabularyStudyModels {

    private VocabularyStudyModels() {}

    public record StudySense(String pos, String definitionEn, String meaningVi) {}

    public record StudyContextNote(String pos, String meaningVi) {}

    public record StudyEntrySource(
        UUID id,
        UUID unitId,
        int position,
        String term,
        String ipaUk,
        String ipaUs,
        List<StudySense> senses,
        List<String> examples,
        List<String> examplesVi,
        List<StudyContextNote> contextNotes,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        long revision
    ) {}

    public record QuestionOption(String key, String text) {}

    /** Server-side locked question stored inside session snapshot JSON (includes correctAnswer). */
    public record SnapshotQuestion(
        UUID id,
        UUID entryId,
        PracticeMode mode,
        String prompt,
        String secondaryPrompt,
        String pos,
        String ipaUk,
        String ipaUs,
        UUID audioId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID imageId,
        List<QuestionOption> options,
        List<String> scrambledLetters,
        List<Integer> wordLengths,
        String maskedTerm,
        List<Integer> blankPositions,
        String clozeSentence,
        Integer spanStart,
        Integer spanEnd,
        String exampleVi,
        String correctAnswer,
        String baseTerm
    ) {}

    public record SessionSnapshot(
        UUID packId,
        UUID unitId,
        String unitTitle,
        String unitLevel,
        long unitRevision,
        PracticeMode mode,
        List<SnapshotQuestion> questions
    ) {}

    /** Learner-facing question view (never exposes correctAnswer before submission). */
    public record QuestionView(
        UUID id,
        UUID entryId,
        PracticeMode mode,
        String prompt,
        String secondaryPrompt,
        String pos,
        String ipaUk,
        String ipaUs,
        UUID audioId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID imageId,
        List<QuestionOption> options,
        List<String> scrambledLetters,
        List<Integer> wordLengths,
        String maskedTerm,
        List<Integer> blankPositions,
        String clozeSentence,
        Integer spanStart,
        Integer spanEnd,
        String exampleVi
    ) {
        public static QuestionView from(SnapshotQuestion q) {
            return new QuestionView(
                q.id(),
                q.entryId(),
                q.mode(),
                q.prompt(),
                q.secondaryPrompt(),
                q.pos(),
                q.ipaUk(),
                q.ipaUs(),
                q.audioId(),
                q.ukAudioId(),
                q.usAudioId(),
                q.imageId(),
                q.options(),
                q.scrambledLetters(),
                q.wordLengths(),
                q.maskedTerm(),
                q.blankPositions(),
                q.clozeSentence(),
                q.spanStart(),
                q.spanEnd(),
                q.exampleVi()
            );
        }
    }

    public record QuestionFeedback(
        UUID questionId,
        UUID entryId,
        String prompt,
        String baseTerm,
        String learnerAnswer,
        String correctAnswer,
        boolean correct
    ) {}

    public record SessionResult(
        int correctCount,
        int totalCount,
        int accuracyPercent,
        List<QuestionFeedback> questions
    ) {}

    public record SessionView(
        UUID id,
        UUID packId,
        UUID unitId,
        String unitTitle,
        PracticeMode mode,
        String status,
        long revision,
        Instant createdAt,
        Instant expiresAt,
        Instant submittedAt,
        List<QuestionView> questions,
        Map<UUID, String> answers,
        SessionResult result
    ) {}

    public record SessionSummary(
        UUID id,
        UUID packId,
        UUID unitId,
        PracticeMode mode,
        String status,
        long revision,
        int questionCount,
        Integer correctCount,
        Integer totalCount,
        Instant createdAt,
        Instant expiresAt,
        Instant submittedAt
    ) {}
}
