package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.domain.authoring.AuthoringCloze;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy.HintLevel;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy.WordView;
import java.util.List;
import org.junit.jupiter.api.Test;

class DictationMaskingPolicyTest {
    private final DictationMaskingPolicy policy = new DictationMaskingPolicy();

    private static final String SENTENCE = "Today Claire met 2026 US delegates.";
    // Tokens: 0=Today, 1=Claire, 2=met, 3=2026, 4=US, 5=delegates
    private static final AuthoringCloze.Targets CLOZE = new AuthoringCloze.Targets(
        List.of(5),
        List.of(1, 2, 5),
        "cloze-v2",
        SENTENCE
    );
    private static final List<WordView> WORDS = List.of(
        new WordView(0, "Today", 0, 300, 0, 5, 0, 1, false),
        new WordView(1, "Claire", 300, 600, 6, 12, 1, 2, false),
        new WordView(2, "met", 600, 800, 13, 16, 2, 3, false),
        new WordView(3, "2026", 800, 1100, 17, 21, 3, 4, false),
        new WordView(4, "US", 1100, 1300, 22, 24, 4, 5, false),
        new WordView(5, "delegates", 1300, 1700, 25, 34, 5, 6, false)
    );

    @Test
    void unattemptedNoneWithoutProperNounHintsMasksEverythingAndHidesNotes() {
        var result = policy.apply(
            SENTENCE,
            CLOZE,
            WORDS,
            List.of(new AuthoringSentence.IpaReviewIssue("NAME", 6, 12, "Claire")),
            List.of(),
            List.of(),
            HintLevel.NONE,
            false,
            false,
            "Hôm nay Claire gặp các đại biểu Hoa Kỳ năm 2026.",
            "təˈdeɪ klɛr ...",
            "US",
            "espeak-1",
            List.of(new AuthoringSentence.Annotation(5, 6, "delegates", "LEXICAL", "đại biểu", "MEDIUM", null, null)),
            List.of(),
            List.of(new AuthoringSentence.Note(0, 1, "note", "USAGE")),
            List.of(new AuthoringSentence.Group(0, 3))
        );

        assertThat(result.text()).isEqualTo("____ ____ ____ ____ ____ ____.");
        assertThat(result.expectedText()).isNull();
        assertThat(result.translation()).isNull();
        assertThat(result.ipa()).isNull();
        assertThat(result.vocabulary()).isEmpty();
        assertThat(result.notes()).isEmpty();
        assertThat(result.thoughtGroups()).isEmpty();
        assertThat(result.properNounHints()).isEmpty();
        assertThat(result.words()).allSatisfy(w -> {
            assertThat(w.masked()).isTrue();
            assertThat(w.surfaceText()).isEqualTo("____");
        });
    }

    @Test
    void properNounHintsRevealNamesNumbersAndAcronymsEvenWhenHintLevelIsNone() {
        var result = policy.apply(
            SENTENCE,
            CLOZE,
            WORDS,
            List.of(new AuthoringSentence.IpaReviewIssue("NAME", 6, 12, "Claire")),
            List.of(),
            List.of(),
            HintLevel.NONE,
            true,
            false,
            "Translation",
            "IPA",
            "US",
            "v1",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );

        assertThat(result.text()).isEqualTo("____ Claire ____ 2026 US ____.");
        assertThat(result.expectedText()).isNull();
        assertThat(result.properNounHints())
            .extracting(DictationMaskingPolicy.ProperNounHint::surfaceText)
            .containsExactly("Claire", "2026", "US");
        assertThat(result.words().get(0).masked()).isTrue();
        assertThat(result.words().get(1).masked()).isFalse();
        assertThat(result.words().get(1).surfaceText()).isEqualTo("Claire");
        assertThat(result.words().get(3).surfaceText()).isEqualTo("2026");
        assertThat(result.words().get(4).surfaceText()).isEqualTo("US");
        assertThat(result.words().get(5).masked()).isTrue();
    }

    @Test
    void easyMasksFewerTokensThanMediumAndRevealsProperNounsWhenEnabled() {
        var easy = policy.apply(
            SENTENCE, CLOZE, WORDS, List.of(), List.of(), List.of(),
            HintLevel.EASY, false, false, "T", "I", "US", "v1",
            List.of(), List.of(), List.of(), List.of()
        );
        var mediumNoProper = policy.apply(
            SENTENCE, CLOZE, WORDS, List.of(), List.of(), List.of(),
            HintLevel.MEDIUM, false, false, "T", "I", "US", "v1",
            List.of(), List.of(), List.of(), List.of()
        );
        var mediumWithProper = policy.apply(
            SENTENCE, CLOZE, WORDS, List.of(), List.of(), List.of(),
            HintLevel.MEDIUM, true, false, "T", "I", "US", "v1",
            List.of(), List.of(), List.of(), List.of()
        );

        assertThat(easy.text()).isEqualTo("Today Claire met 2026 US ____.");
        assertThat(mediumNoProper.text()).isEqualTo("Today ____ ____ 2026 US ____.");
        assertThat(mediumWithProper.text()).isEqualTo("Today Claire ____ 2026 US ____.");
    }

    @Test
    void shortSentenceWithEmptyClozeFallsBackToFullMasking() {
        var shortCloze = new AuthoringCloze.Targets(List.of(), List.of(), "cloze-v2", "Go now.");
        var result = policy.apply(
            "Go now.", shortCloze, List.of(), List.of(), List.of(), List.of(),
            HintLevel.EASY, false, false, "Đi ngay.", null, null, null,
            List.of(), List.of(), List.of(), List.of()
        );

        assertThat(result.text()).isEqualTo("____ ____.");
        assertThat(result.expectedText()).isNull();
    }

    @Test
    void attemptedSentenceRevealsExpectedTextWordsTranslationAndNotes() {
        var vocab = List.of(new AuthoringSentence.Annotation(5, 6, "delegates", "LEXICAL", "đại biểu", "MEDIUM", null, null));
        var notes = List.of(new AuthoringSentence.Note(0, 1, "note", "USAGE"));
        var groups = List.of(new AuthoringSentence.Group(0, 3));

        var result = policy.apply(
            SENTENCE, CLOZE, WORDS, List.of(), List.of(), List.of(),
            HintLevel.NONE, false, true, "Translation", "IPA", "US", "v1",
            vocab, List.of(), notes, groups
        );

        assertThat(result.text()).isEqualTo(SENTENCE);
        assertThat(result.expectedText()).isEqualTo(SENTENCE);
        assertThat(result.translation()).isEqualTo("Translation");
        assertThat(result.ipa()).isEqualTo("IPA");
        assertThat(result.vocabulary()).hasSize(1);
        assertThat(result.notes()).hasSize(1);
        assertThat(result.thoughtGroups()).hasSize(1);
        assertThat(result.words()).allSatisfy(w -> assertThat(w.masked()).isFalse());
    }

    @Test
    void invalidHintLevelThrowsValidationError() {
        assertThat(HintLevel.parse(null)).isEqualTo(HintLevel.NONE);
        assertThat(HintLevel.parse("easy")).isEqualTo(HintLevel.EASY);
        assertThat(HintLevel.parse("MEDIUM")).isEqualTo(HintLevel.MEDIUM);
        assertThatThrownBy(() -> HintLevel.parse("HARD"))
            .isInstanceOf(RequestValidationException.class);
    }
}
