package com.lyreo.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.vocabulary.domain.PracticeMode;
import com.lyreo.vocabulary.domain.VocabularyAnswerGrader;
import com.lyreo.vocabulary.domain.VocabularyExerciseGenerator;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.QuestionOption;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.SnapshotQuestion;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.StudyContextNote;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.StudyEntrySource;
import com.lyreo.vocabulary.domain.VocabularyStudyModels.StudySense;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VocabularyExerciseGeneratorTest {

    private final VocabularyExerciseGenerator generator = new VocabularyExerciseGenerator();
    private final VocabularyAnswerGrader grader = new VocabularyAnswerGrader();

    @Test
    void enforcesAllSixModeRulesAndAvailability() {
        UUID audio1 = UUID.randomUUID();
        UUID audio2 = UUID.randomUUID();

        List<StudyEntrySource> entries = List.of(
            entry(1, "achieve", "đạt được", "to succeed in finishing something",
                "She achieved her goals after years of effort.", audio1),
            entry(2, "accomplish", "đạt được", "to succeed in doing something",
                "They accomplished the mission.", audio2),
            entry(3, "pepper", "hạt tiêu", "a spicy seasoning",
                "Add some pepper to the soup.", null),
            entry(4, "run", "chạy", "to move quickly on foot",
                "He is running along the river.", null),
            entry(5, "resilient", "kiên cường", "able to recover quickly",
                "A completely unrelated sentence.", null)
        );

        Map<PracticeMode, Integer> availability = generator.modeAvailability(entries);
        // Only 2 entries have audio (achieve, accomplish)
        assertThat(availability.get(PracticeMode.LISTEN_WRITE)).isEqualTo(2);
        // achieve ("achieved"), accomplish ("accomplished"), pepper ("pepper"), run ("running") match; resilient does not
        assertThat(availability.get(PracticeMode.CONTEXT_CLOZE)).isEqualTo(4);
        assertThat(availability.get(PracticeMode.LETTER_SCRAMBLE)).isEqualTo(5);
        assertThat(availability.get(PracticeMode.MISSING_LETTERS)).isEqualTo(5);
        assertThat(availability.get(PracticeMode.CHOOSE_MEANING)).isEqualTo(5);
        assertThat(availability.get(PracticeMode.CHOOSE_WORD)).isEqualTo(5);

        // CHOOSE_MEANING: no duplicate-meaning distractors ("đạt được" appears at most once)
        List<SnapshotQuestion> meaningQs = generator.generateQuestions(entries, PracticeMode.CHOOSE_MEANING, 10, 42L);
        for (SnapshotQuestion q : meaningQs) {
            List<String> optionTexts = q.options().stream().map(QuestionOption::text).toList();
            assertThat(optionTexts).hasSizeBetween(2, 4);
            assertThat(optionTexts.stream().map(grader::normalize).toList()).doesNotHaveDuplicates();
        }

        // CHOOSE_WORD: for "achieve" ("đạt được"), "accomplish" (also "đạt được") must NOT be a distractor
        List<SnapshotQuestion> wordQs = generator.generateQuestions(entries, PracticeMode.CHOOSE_WORD, 10, 42L);
        SnapshotQuestion achieveWordQ = wordQs.stream()
            .filter(q -> q.baseTerm().equals("achieve"))
            .findFirst()
            .orElseThrow();
        assertThat(achieveWordQ.options())
            .extracting(QuestionOption::text)
            .contains("achieve")
            .doesNotContain("accomplish");

        // LETTER_SCRAMBLE: exact letter multiplicity for "pepper" (3 p, 2 e, 1 r) and scrambled
        List<SnapshotQuestion> scrambleQs = generator.generateQuestions(entries, PracticeMode.LETTER_SCRAMBLE, 10, 42L);
        SnapshotQuestion pepperScramble = scrambleQs.stream()
            .filter(q -> q.baseTerm().equals("pepper"))
            .findFirst()
            .orElseThrow();
        Map<String, Long> freq = new HashMap<>();
        for (String letter : pepperScramble.scrambledLetters()) {
            freq.merge(letter, 1L, Long::sum);
        }
        assertThat(freq).containsEntry("p", 3L).containsEntry("e", 2L).containsEntry("r", 1L);
        assertThat(String.join("", pepperScramble.scrambledLetters())).isNotEqualTo("pepper");

        // MISSING_LETTERS: at least 1 blank
        List<SnapshotQuestion> missingQs = generator.generateQuestions(entries, PracticeMode.MISSING_LETTERS, 10, 42L);
        for (SnapshotQuestion q : missingQs) {
            assertThat(q.blankPositions()).isNotEmpty();
            assertThat(q.maskedTerm()).contains("_");
        }

        // CONTEXT_CLOZE: locked span & inflected answer ("achieved", "running")
        List<SnapshotQuestion> clozeQs = generator.generateQuestions(entries, PracticeMode.CONTEXT_CLOZE, 10, 42L);
        SnapshotQuestion achieveCloze = clozeQs.stream()
            .filter(q -> q.baseTerm().equals("achieve"))
            .findFirst()
            .orElseThrow();
        assertThat(achieveCloze.correctAnswer()).isEqualTo("achieved");
        assertThat(achieveCloze.clozeSentence()).isEqualTo("She _____ her goals after years of effort.");
        assertThat(achieveCloze.spanStart()).isEqualTo(4);
        assertThat(achieveCloze.spanEnd()).isEqualTo(12);

        SnapshotQuestion runCloze = clozeQs.stream()
            .filter(q -> q.baseTerm().equals("run"))
            .findFirst()
            .orElseThrow();
        assertThat(runCloze.correctAnswer()).isEqualTo("running");
        assertThat(runCloze.clozeSentence()).isEqualTo("He is _____ along the river.");

        // NFKC + trimmed + collapsed whitespace + case-insensitive grading
        assertThat(grader.isCorrect(achieveCloze, "  \uFF21\uFF23\uFF28\uFF29\uFF25\uFF36\uFF25\uFF24  ")).isTrue();
        assertThat(grader.isCorrect(achieveCloze, "achieve")).isFalse();
    }

    private static StudyEntrySource entry(
        int pos,
        String term,
        String meaningVi,
        String defEn,
        String example,
        UUID usAudioId
    ) {
        return new StudyEntrySource(
            UUID.randomUUID(),
            UUID.randomUUID(),
            pos,
            term,
            "/" + term + "/",
            "/" + term + "/",
            List.of(new StudySense("v.", defEn, meaningVi)),
            List.of(example, "Another sentence."),
            List.of("Ví dụ tiếng Việt", "Câu khác"),
            List.of(new StudyContextNote("v.", meaningVi)),
            null,
            null,
            usAudioId,
            null,
            null,
            1L
        );
    }
}
