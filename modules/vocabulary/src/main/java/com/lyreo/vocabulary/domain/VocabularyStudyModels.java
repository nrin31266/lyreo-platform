package com.lyreo.vocabulary.domain;

import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Domain types for vocabulary study catalog, practice sessions, guess mode, and flashcard SRS. */
public final class VocabularyStudyModels {

    private VocabularyStudyModels() {}

    public enum FlashcardScope {
        DUE_AND_NEW,
        ALL
    }

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
        String baseTerm,
        UUID unitId,
        Integer maxHints
    ) {
        public SnapshotQuestion(
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
        ) {
            this(
                id, entryId, mode, prompt, secondaryPrompt, pos, ipaUk, ipaUs,
                audioId, ukAudioId, usAudioId, imageId, options, scrambledLetters, wordLengths,
                maskedTerm, blankPositions, clozeSentence, spanStart, spanEnd, exampleVi,
                correctAnswer, baseTerm, null, null
            );
        }
    }

    public record QuestionState(
        List<Integer> revealedPositions,
        boolean checked,
        boolean gaveUp
    ) {
        public static final QuestionState EMPTY = new QuestionState(List.of(), false, false);
    }

    public record SessionSnapshot(
        UUID packId,
        UUID unitId,
        String unitTitle,
        String unitLevel,
        long unitRevision,
        PracticeMode mode,
        List<SnapshotQuestion> questions
    ) {}

    /** Learner-facing question view (never exposes correctAnswer before submission or check). */
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
        String exampleVi,
        Integer maxHints,
        Integer hintsUsed,
        Integer hintsRemaining,
        List<Integer> revealedPositions,
        Boolean checked,
        Boolean gaveUp
    ) {
        public static QuestionView from(SnapshotQuestion q) {
            return from(q, null);
        }

        public static QuestionView from(SnapshotQuestion q, QuestionState state) {
            boolean isGuess = q.mode() == PracticeMode.GUESS_WORD;
            List<Integer> revealed = state != null && state.revealedPositions() != null
                ? List.copyOf(state.revealedPositions())
                : List.of();
            String effectiveMasked = isGuess && !revealed.isEmpty()
                ? VocabularyExerciseGenerator.maskWithRevealed(q.baseTerm(), revealed)
                : q.maskedTerm();
            Integer maxHints = q.maxHints();
            Integer hintsUsed = isGuess ? revealed.size() : null;
            Integer hintsRemaining = isGuess && maxHints != null ? Math.max(0, maxHints - revealed.size()) : null;
            List<Integer> exposedRevealed = isGuess ? revealed : null;
            Boolean checkedFlag = isGuess
                ? Boolean.valueOf(state != null && state.checked())
                : (state != null && state.checked() ? Boolean.TRUE : null);
            Boolean gaveUpFlag = isGuess
                ? Boolean.valueOf(state != null && state.gaveUp())
                : (state != null && state.checked() ? Boolean.valueOf(state.gaveUp()) : null);
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
                effectiveMasked,
                q.blankPositions(),
                q.clozeSentence(),
                q.spanStart(),
                q.spanEnd(),
                q.exampleVi(),
                maxHints,
                hintsUsed,
                hintsRemaining,
                exposedRevealed,
                checkedFlag,
                gaveUpFlag
            );
        }
    }

    public record HintResponse(
        UUID questionId,
        String maskedTerm,
        List<Integer> revealedPositions,
        int hintsUsed,
        int hintsRemaining
    ) {}

    public record CheckQuestionResponse(
        long revision,
        UUID questionId,
        UUID entryId,
        String prompt,
        String baseTerm,
        String learnerAnswer,
        String correctAnswer,
        boolean correct,
        int hintsUsed,
        boolean gaveUp
    ) {}

    public record QuestionFeedback(
        UUID questionId,
        UUID entryId,
        String prompt,
        String baseTerm,
        String learnerAnswer,
        String correctAnswer,
        boolean correct,
        int hintsUsed,
        boolean gaveUp
    ) {
        public QuestionFeedback(
            UUID questionId,
            UUID entryId,
            String prompt,
            String baseTerm,
            String learnerAnswer,
            String correctAnswer,
            boolean correct
        ) {
            this(questionId, entryId, prompt, baseTerm, learnerAnswer, correctAnswer, correct, 0, false);
        }
    }

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
        String unitTitle,
        PracticeMode mode,
        String status,
        long revision,
        int questionCount,
        Integer correctCount,
        Integer totalCount,
        Instant createdAt,
        Instant expiresAt,
        Instant submittedAt
    ) {
        public SessionSummary(
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
        ) {
            this(id, packId, unitId, null, mode, status, revision, questionCount,
                correctCount, totalCount, createdAt, expiresAt, submittedAt);
        }
    }

    public record IntervalPreview(
        Instant nextReviewAt,
        long intervalDays
    ) {}

    public record FlashcardCounts(
        Integer dueAndNewUnit,
        Integer allUnit,
        int dueAndNewPack,
        int allPack,
        int dueCount,
        int newCount,
        int learningCount,
        int masteredCount
    ) {}

    public record FlashcardItem(
        UUID entryId,
        UUID unitId,
        String unitLevel,
        int position,
        String term,
        long revision,
        VocabularyCatalog.LearningContent content,
        UUID imageId,
        UUID ukAudioId,
        UUID usAudioId,
        UUID exampleAudioId,
        UUID secondExampleAudioId,
        String imageUrl,
        String ukAudioUrl,
        String usAudioUrl,
        String exampleAudioUrl,
        String secondExampleAudioUrl,
        String srsState,
        int reviewCount,
        int lapseCount,
        double stability,
        double difficulty,
        Instant nextReviewAt,
        Map<SpacedRepetitionScheduler.Rating, IntervalPreview> intervalPreviews
    ) {}

    public record FlashcardDeckResponse(
        UUID packId,
        UUID unitId,
        FlashcardScope scope,
        FlashcardCounts counts,
        List<FlashcardItem> items
    ) {}

    public record FlashcardReviewResponse(
        UUID entryId,
        UUID unitId,
        UUID packId,
        String srsState,
        Instant nextReviewAt,
        double stability,
        double difficulty,
        int reviewCount,
        int lapseCount,
        Map<SpacedRepetitionScheduler.Rating, IntervalPreview> intervalPreviews
    ) {}
}
