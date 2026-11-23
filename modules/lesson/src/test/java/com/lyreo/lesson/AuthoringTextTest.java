package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoringTextTest {
    @Test void identifiesRepeatedSuffixAsAReviewHintWithoutChangingTheWords() {
        assertThat(AuthoringText.repeatsPreviousSuffix("They sting him on his elbow.","his elbow.")).isTrue();
        assertThat(AuthoringText.repeatsPreviousSuffix("Hello!","Hello!")).isTrue();
        assertThat(AuthoringText.repeatsPreviousSuffix("A shell.","hell.")).isFalse();
        assertThat(AuthoringText.repeatsPreviousSuffix("Hello!","Next sentence.")).isFalse();
    }
    @Test void distinguishesCoverageAndIntervalFailuresWithoutReturningTranscriptText() {
        var sentences=List.of(sentence("Hello everyone."));
        assertThatThrownBy(()->AuthoringText.mapTiming(sentences,List.of(word("Hello",0,100)),45837))
            .isInstanceOfSatisfying(com.lyreo.lesson.domain.authoring.TimingValidationException.class,
                failure->assertThat(failure.code()).isEqualTo("TIMING_COVERAGE_MISMATCH"));
        assertThatThrownBy(()->AuthoringText.mapTiming(sentences,List.of(word("Hello",0,100),word("everyone",100,45840)),45837))
            .isInstanceOfSatisfying(com.lyreo.lesson.domain.authoring.TimingValidationException.class,
                failure->{assertThat(failure.code()).isEqualTo("TIMING_OUT_OF_BOUNDS");
                    assertThat(failure.getMessage()).contains("word=1", "45840", "45837").doesNotContain("everyone");});
    }

    @Test void splitsQuotesAbbreviationsAndDecimalNumbers() {
        assertThat(AuthoringText.split("Dr. Brown said, \"It costs 2.5 dollars.\" Is that right? Yes!"))
            .extracting(s->s.text).containsExactly("Dr. Brown said, \"It costs 2.5 dollars.\"","Is that right?","Yes!");
    }
    @Test void rejoinsAutomaticCaptionFragmentsAtSentencePunctuationAndRetainsSourceWindows() {
        var cues = List.of(sentence("Welcome to this A2"), sentence("English lesson. I hope"),
            sentence("you're ready. Today we start"));
        Map<String,Object> evidence = Map.of("segments", List.of(
            Map.of("text", cues.get(0).text, "start_ms", 0L, "end_ms", 1000L),
            Map.of("text", cues.get(1).text, "start_ms", 1000L, "end_ms", 2000L),
            Map.of("text", cues.get(2).text, "start_ms", 2000L, "end_ms", 3000L)));
        var rows = AuthoringText.joinCaptionCues(cues, evidence);
        assertThat(rows).extracting(row -> row.text).containsExactly(
            "Welcome to this A2 English lesson.", "I hope you're ready.", "Today we start");
        assertThat(rows).extracting(row -> row.coarseStartMs).containsExactly(0L, 1000L, 2000L);
        var draft = new AuthoringLesson();
        draft.endMs = 3000;
        draft.sentences = rows;
        draft.timingEvidence = evidence;
        assertThat(LessonAuthoringService.alignmentSegments(draft)).isEqualTo(evidence.get("segments"));
        var unpunctuated = List.of(sentence("welcome to this"), sentence("English lesson"));
        assertThat(AuthoringText.joinCaptionCues(unpunctuated, Map.of())).isEqualTo(unpunctuated);
    }
    @Test void mapsCompoundsContractionsAndUnicodeWithoutWhitespaceCounting() {
        var sentences=List.of(sentence("It’s ice-cream."),sentence("Good café."));
        AuthoringText.mapTiming(sentences,List.of(word("It",0,100),word("s",100,200),word("icecream",200,500),
            word("Good",600,700),word("café",700,900)),1000);
        assertThat(sentences.getFirst().words).extracting(AuthoringSentence.Word::text).containsExactly("It’s","ice-cream");
        assertThat(sentences.getFirst().words.getLast().tokenEnd()).isEqualTo(3);
        assertThat(sentences.getLast().words.getFirst().startMs()).isEqualTo(600);
    }
    @Test void lexicalInvalidationDistinguishesChangedWordBoundaries() {
        assertThat(AuthoringText.lexicalSequence("a name")).isNotEqualTo(AuthoringText.lexicalSequence("an ame"));
        assertThat(AuthoringText.lexicalSequence("Ice-cream!")).isEqualTo(AuthoringText.lexicalSequence("ice cream"));
    }
    @Test void coalescesObservedOverlapAndTouchingZeroWithoutInventingDurations() {
        var words=AuthoringText.coalesce(List.of(word("We",0,100),word("can",100,100),word("learn",100,200),word("now",180,300)));
        assertThat(words).hasSize(2);
        assertThat(words.getFirst()).isEqualTo(word("We can",0,100));
        assertThat(words.getLast()).isEqualTo(word("learn now",100,300));
        var sentence=sentence("We can learn now.");
        AuthoringText.mapTiming(List.of(sentence),words,400);
        assertThat(sentence.issues).contains("GROUPED_TIMING");
        var draft=new AuthoringLesson();draft.endMs=400;draft.sentences=List.of(sentence);
        assertThatCode(()->LessonAuthoringService.validateTiming(draft)).doesNotThrowAnyException();
    }
    @Test void coalescesBackwardStartingNestedOverlapsUntilTheStackIsDisjoint() {
        var grouped=AuthoringText.coalesce(List.of(word("A",0,100),word("B",120,200),word("C",90,220)));
        assertThat(grouped).containsExactly(word("A B C",0,220));
    }
    @Test void acceptsActualSttTimestampsShapeAndUsesSegmentEvidenceForCoarseAnchors() {
        var words=AuthoringText.observed(java.util.Map.of("timestamps",List.of(java.util.Map.of("word","We","start_ms",0,"end_ms",100))));
        assertThat(words).containsExactly(word("We",0,100));
        var sentences=List.of(sentence("We learn."),sentence("Practice helps."));
        AuthoringText.seedCoarse(sentences,java.util.Map.of("segments",List.of(
            java.util.Map.of("text","We learn.","start_ms",10,"end_ms",500),
            java.util.Map.of("text","Practice helps.","start_ms",600,"end_ms",1000))));
        assertThat(sentences.getLast().coarseStartMs).isEqualTo(600);
        assertThat(sentences.getLast().words).isEmpty();
    }
    @Test void isolatedZerosAndTruncationCannotPassTimingGate() {
        var sentence=sentence("We learn.");
        AuthoringText.mapTiming(List.of(sentence),List.of(word("We",0,100),word("learn",300,300)),400);
        var draft=new AuthoringLesson();draft.endMs=400;draft.sentences=List.of(sentence);
        assertThatThrownBy(()->LessonAuthoringService.validateTiming(draft)).isInstanceOfSatisfying(com.lyreo.lesson.domain.authoring.TimingValidationException.class,
                failure->assertThat(failure.code()).isEqualTo("TIMING_ZERO_DURATION"));
        assertThatThrownBy(()->AuthoringText.mapTiming(List.of(sentence),List.of(word("We",0,100)),400))
            .hasMessageContaining("coverage");
    }
    @Test void alignmentCanRetainUnresolvedTokensOnlyAsExplicitObservedGroupsWithinTheirSentence() {
        var rows=List.of(sentence("Friday Saturday Sunday."),sentence("Hongdae, for example."));
        AuthoringText.mapTiming(rows,List.of(word("Friday",100,500),word("Saturday",700,700),word("Sunday",700,700),
            word("Hongdae",900,900),word("for",1100,1100),word("example",1100,1600)),2000);
        AuthoringText.groupUnresolvedTiming(rows);
        assertThat(rows.getFirst().words).containsExactly(new AuthoringSentence.Word("Friday Saturday Sunday",100,700,0,22,0,3));
        assertThat(rows.getLast().words).containsExactly(new AuthoringSentence.Word("Hongdae, for example",900,1600,0,20,0,3));
        assertThat(rows).allSatisfy(row->assertThat(row.issues).contains("GROUPED_TIMING").doesNotContain("TIMING_ZERO_DURATION"));
        var draft=new AuthoringLesson();draft.endMs=2000;draft.sentences=rows;
        assertThatCode(()->LessonAuthoringService.validateTiming(draft)).doesNotThrowAnyException();
    }
    @Test void groupingCannotBorrowTimingFromAnotherSentenceOrRepairReversedIntervals() {
        var rows=List.of(sentence("Hello."),sentence("World."));
        AuthoringText.mapTiming(rows,List.of(word("Hello",100,300),word("World",500,500)),1000);
        AuthoringText.groupUnresolvedTiming(rows);
        assertThat(rows.getLast().words).hasSize(1).allSatisfy(word->assertThat(word.endMs()).isEqualTo(word.startMs()));
        var draft=new AuthoringLesson();draft.endMs=1000;draft.sentences=rows;
        assertThatThrownBy(()->LessonAuthoringService.validateTiming(draft)).hasMessageContaining("word=1");
        AuthoringText.mapTiming(rows,List.of(word("Hello",100,100),word("World",500,500)),1000);
        AuthoringText.groupUnresolvedTiming(rows);
        assertThatThrownBy(()->LessonAuthoringService.validateTiming(draft)).hasMessageContaining("word=0");
        assertThatThrownBy(()->AuthoringText.mapTiming(List.of(sentence("Hello world.")),List.of(word("Hello",0,200),word("world",400,300)),1000))
            .isInstanceOf(com.lyreo.lesson.domain.authoring.TimingValidationException.class);
    }
    @Test void refusesToDivideOneObservedIntervalAcrossApprovedSentences() {
        assertThatThrownBy(()->AuthoringText.mapTiming(List.of(sentence("Ice."),sentence("Cream.")),List.of(word("icecream",0,100)),200))
            .hasMessageContaining("sentence boundaries");
        assertThatThrownBy(()->AuthoringText.coalesce(List.of(sentence("Ice."),sentence("Cream.")),List.of(word("icecream",0,100))))
            .hasMessageContaining("sentence boundaries");
    }
    @Test void trailingZeroDurationWordDoesNotJoinTheFollowingSentence() {
        var sentences=List.of(sentence("We learn."),sentence("English daily."));
        var words=AuthoringText.coalesce(sentences,List.of(word("We",0,100),word("learn",140,140),
            word("English",140,300),word("daily",300,400)));
        AuthoringText.mapTiming(sentences,words,500);
        AuthoringText.groupUnresolvedTiming(sentences);
        var draft=new AuthoringLesson();draft.endMs=500;draft.sentences=sentences;
        assertThatCode(()->LessonAuthoringService.validateTiming(draft)).doesNotThrowAnyException();
        assertThat(sentences.getFirst().words).extracting(AuthoringSentence.Word::text).containsExactly("We learn");
        assertThat(sentences.getLast().words.getFirst().startMs()).isEqualTo(140);
    }
    @Test void positiveOverlapAcrossSentencesStillFailsValidation() {
        var sentences=List.of(sentence("We."),sentence("Learn."));
        AuthoringText.mapTiming(sentences,AuthoringText.coalesce(sentences,List.of(word("We",0,100),word("Learn",90,200))),300);
        var draft=new AuthoringLesson();draft.endMs=300;draft.sentences=sentences;
        assertThatThrownBy(()->LessonAuthoringService.validateTiming(draft))
            .isInstanceOfSatisfying(com.lyreo.lesson.domain.authoring.TimingValidationException.class,
                failure->assertThat(failure.code()).isEqualTo("TIMING_OVERLAP"));
    }
    private static AuthoringSentence sentence(String text) {return new AuthoringSentence(UUID.randomUUID(),text,"LEARN");}
    private static AuthoringText.ObservedWord word(String text,long start,long end) {return new AuthoringText.ObservedWord(text,start,end);}
}
