package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.lesson.domain.authoring.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthoringContentTest {
    @Test void anEmptyTranscriptNeverHasCompletedContent() {
        var lesson=new AuthoringLesson();
        assertThat(AuthoringContent.complete(lesson)).isFalse();
        lesson.options=new AuthoringOptions(Set.of(),Set.of(),"vi-VN","AUTO",20,8,"DISABLED","US","LOCAL");
        assertThat(AuthoringContent.complete(lesson)).isFalse();
    }
    @Test void profileUsesWeightedEvidenceAndManualReviewBecomesStaleAfterTextEdit() {
        var lesson=new AuthoringLesson();
        var first=new AuthoringSentence(UUID.randomUUID(),"We learn every day.","LEARN");
        var second=new AuthoringSentence(UUID.randomUUID(),"Hello.","LEARN");
        first.cefrSuggestion=AuthoringProfile.Cefr.B1;first.contentTypeSuggestion=AuthoringProfile.ContentType.STORY;
        second.cefrSuggestion=AuthoringProfile.Cefr.A1;second.contentTypeSuggestion=AuthoringProfile.ContentType.NEWS;
        lesson.sentences=List.of(first,second);lesson.profile=AuthoringProfile.aggregate(lesson);
        assertThat(lesson.profile.cefr).isEqualTo(AuthoringProfile.Cefr.B1);
        assertThat(lesson.profile.contentType).isEqualTo(AuthoringProfile.ContentType.STORY);
        lesson.profile.manual=true;
        assertThat(AuthoringContent.current(lesson,0,"PROFILE")).isTrue();
        first.text="We learn together.";
        assertThat(AuthoringContent.current(lesson,0,"PROFILE")).isFalse();
        assertThat(AuthoringProfile.aggregate(new AuthoringLesson()).contentType).isEqualTo(AuthoringProfile.ContentType.UNKNOWN);
    }
    @Test void automaticBudgetGrowsWithWordsAndCustomBudgetIsAnExplicitChoice() {
        var lesson = new AuthoringLesson();
        lesson.sentences = java.util.stream.IntStream.range(0,100)
            .mapToObj(i -> new AuthoringSentence(UUID.randomUUID(), "word ".repeat(15), "LEARN")).toList();
        assertThat(AuthoringContent.vocabularyLimit(lesson)).isEqualTo(15);
        assertThat(AuthoringContent.grammarLimit(lesson)).isEqualTo(6);
        lesson.sentences = List.of(new AuthoringSentence(UUID.randomUUID(), "word ".repeat(10000), "LEARN"));
        assertThat(AuthoringContent.vocabularyLimit(lesson)).isEqualTo(100);
        assertThat(AuthoringContent.grammarLimit(lesson)).isEqualTo(40);
        lesson.options=new AuthoringOptions(Set.of(),Set.of("LEXICAL","GRAMMAR"),"vi-VN","CUSTOM",7,2,"DISABLED","US");
        assertThat(AuthoringContent.vocabularyLimit(lesson)).isEqualTo(7);
        assertThat(AuthoringContent.grammarLimit(lesson)).isEqualTo(2);
    }
    @Test void spansUseExactUnicodeTextAndExplicitOccurrence() {
        var sentence = new AuthoringSentence(UUID.randomUUID(), "We don’t give up; don’t give up.", "LEARN");
        var span = AuthoringContent.annotation(sentence, "don’t give up", "Keep trying", 2, "LEXICAL");
        assertThat(span.tokenStart()).isEqualTo(4);
        assertThat(span.tokenEnd()).isEqualTo(7);
        assertThatThrownBy(() -> AuthoringContent.annotation(sentence,"give", "note",3,"LEXICAL"))
            .hasMessageContaining("occurrence");
        assertThatThrownBy(() -> AuthoringContent.annotation(sentence,"on’t", "note",1,"LEXICAL"))
            .hasMessageContaining("token");
    }
    @Test void disabledFeaturesDoNotNeedGenerationAndKeysDependOnTextNotTiming() {
        var lesson = new AuthoringLesson();
        var sentence = new AuthoringSentence(UUID.randomUUID(), "Hello world.", "LEARN");
        lesson.sentences = List.of(sentence);
        var key = AuthoringContent.key(lesson,0,"TRANSLATION");
        sentence.words = List.of(new AuthoringSentence.Word("Hello world",0,1000,0,11,0,2));
        assertThat(AuthoringContent.key(lesson,0,"TRANSLATION")).isEqualTo(key);
        sentence.text = "Goodbye world.";
        assertThat(AuthoringContent.key(lesson,0,"TRANSLATION")).isNotEqualTo(key);
        lesson.options = new AuthoringOptions(Set.of(),Set.of(),"vi-VN","AUTO",20,8,"DISABLED","US","LOCAL");
        assertThat(AuthoringContent.features(lesson.options)).isEmpty();
        assertThat(AuthoringContent.complete(lesson)).isTrue();
    }
}
