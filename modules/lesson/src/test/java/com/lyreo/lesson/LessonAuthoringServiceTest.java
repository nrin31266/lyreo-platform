package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LessonAuthoringServiceTest {
    @Test void manualNotesDoNotConsumeTheAutomaticGenerationBudget() {
        lesson.options=new com.lyreo.lesson.domain.authoring.AuthoringOptions(java.util.Set.of(),java.util.Set.of("LEXICAL","GRAMMAR"),"vi-VN","CUSTOM",0,0,"DISABLED","US");
        var sentence=lesson.sentences.getFirst();
        var note=com.lyreo.lesson.domain.authoring.AuthoringContent.annotation(sentence,"learn","Học",1,"LEXICAL");
        service.saveContent(lesson.id,5,sentence.id,new LessonAuthoringService.ContentInput(null,null,false,List.of(note),List.of(),false,false));
        assertThat(sentence.vocabulary).containsExactly(note);
        assertThat(sentence.manualFields).contains("LEXICAL");
        verifyNoInteractions(jobs);
    }
    @Test void partialManualEditLeavesOtherMissingFeaturesAvailableForGeneration() {
        var sentence=lesson.sentences.getFirst();
        service.saveContent(lesson.id,5,sentence.id,new LessonAuthoringService.ContentInput("Bản sửa",null,false,List.of(),List.of(),false,false));
        assertThat(sentence.manualFields).containsExactly("TRANSLATION");
        assertThat(com.lyreo.lesson.domain.authoring.AuthoringContent.needed(lesson)).isTrue();
    }
    @Test void manualIpaSurvivesTextEditsButNeedsExplicitReviewAgain() {
        var sentence=lesson.sentences.getFirst();
        sentence.ipa="reference";sentence.manualFields.add("IPA");
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(sentence.id,"We teach.",null)));
        assertThat(lesson.sentences.getFirst().ipa).isEqualTo("reference");
        assertThat(com.lyreo.lesson.domain.authoring.AuthoringContent.current(lesson,0,"IPA")).isFalse();
        verifyNoInteractions(jobs);
    }
    private AuthoringSentence lessonSentence() {return lesson.sentences.getFirst();}
    @Test void disabledContentCanBeReviewedWithoutAiAndOptionsDoNotEnqueue() {
        service.contentOptions(lesson.id,5,new com.lyreo.lesson.domain.authoring.AuthoringOptions(java.util.Set.of(),java.util.Set.of(),"vi-VN","AUTO",20,8,"DISABLED","US","LOCAL"));
        assertThat(service.reviewContent(lesson.id,5).semanticRevision).isNull();
        verifyNoInteractions(jobs);
    }
    @Test void contentReviewDoesNotPretendThatWarningsWereChecked() {
        var sentence=lesson.sentences.getFirst();
        for(String feature:com.lyreo.lesson.domain.authoring.AuthoringContent.features(lesson.options))
            sentence.contentKeys.put(feature,com.lyreo.lesson.domain.authoring.AuthoringContent.key(lesson,0,feature));
        sentence.translation="Chúng ta học.";sentence.ipa="reference";sentence.ipaNeedsReview=true;
        assertThat(service.reviewContent(lesson.id,5).semanticRevision).isNull();
        sentence.ipaNeedsReview=false;
        assertThat(service.reviewContent(lesson.id,5).semanticRevision).isNull();
    }
    private final AuthoringRepository repository=mock(AuthoringRepository.class);
    private final BackgroundJobService jobs=mock(BackgroundJobService.class);
    private final LessonAuthoringService service=new LessonAuthoringService(repository,mock(AuthoringSourcePort.class),jobs);
    private AuthoringLesson lesson;
    @BeforeEach void setup() {
        service.setReuseDraftTiming(true);
        lesson=new AuthoringLesson();lesson.id=UUID.randomUUID();lesson.revision=5;lesson.transcriptRevision=2;
        lesson.endMs=1000;lesson.timingStatus="DRAFT_VALID";lesson.status="CONTENT_REVIEW";
        var sentence=new AuthoringSentence(UUID.randomUUID(),"We learn.","LEARN");
        AuthoringText.mapTiming(List.of(sentence),List.of(new AuthoringText.ObservedWord("We",0,200),new AuthoringText.ObservedWord("learn",200,500)),1000);
        sentence.dictationEligible=true;sentence.shadowingEligible=true;
        lesson.sentences=List.of(sentence);lesson.approvedRevision=2L;lesson.karaokeReviewedRevision=2L;lesson.semanticRevision=2L;
        when(repository.find(lesson.id,true)).thenReturn(Optional.of(lesson));
    }
    @Test void draftTimingRequiresLocalUntilQualityPolicyAllowsReuse() {
        service.setReuseDraftTiming(false);
        assertThatThrownBy(()->service.karaoke(lesson.id,5)).hasMessageContaining("Local alignment");
        service.setReuseDraftTiming(true);
        assertThat(service.karaoke(lesson.id,5).karaokeReviewedRevision).isEqualTo(2L);
    }
    @Test void rejectsLessonLongerThanTenMinutes() {
        var source=mock(AuthoringSourcePort.class);
        when(source.inspect("source")).thenReturn(new AuthoringSourcePort.SourceInfo("Abc_123-xYz","Video",3_600_000,true));
        var bounded=new LessonAuthoringService(repository,source,jobs);
        assertThatThrownBy(()->bounded.create("Lesson","source",0,600_001))
            .isInstanceOf(com.lyreo.contracts.errors.RequestValidationException.class);
        verify(repository,never()).create(any());verifyNoInteractions(jobs);
    }
    @Test void punctuationAndSplitReuseTimingWithoutAiJobs() {
        var first=lesson.sentences.getFirst();
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,"We!","LEARN"),new LessonAuthoringService.SentenceInput(null,"Learn!","WATCH_ONLY")));
        assertThat(lesson.timingStatus).isEqualTo("DRAFT_VALID");
        assertThat(lesson.sentences.getLast().words.getFirst().startMs()).isEqualTo(200);
        assertThat(lesson.transcriptRevision).isEqualTo(3);assertThat(lesson.approvedRevision).isNull();
        verifyNoInteractions(jobs);
    }
    @Test void lexicalEditInvalidatesTimingButRetainsCoarseEvidence() {
        var first=lesson.sentences.getFirst();
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,"We teach.","LEARN")));
        assertThat(lesson.timingStatus).isEqualTo("STALE");assertThat(lesson.sentences.getFirst().words).isEmpty();
        assertThat(lesson.sentences.getFirst().coarseEndMs).isEqualTo(500);assertThat(lesson.semanticRevision).isNull();
    }
    @Test void legacyHiddenTextParticipatesInAlignmentAndExplicitSaveNormalizesIt() {
        var first=lesson.sentences.getFirst();
        var repeated=new AuthoringSentence(UUID.randomUUID(),"Learn.","WATCH_ONLY");
        repeated.hidden=true;repeated.coarseStartMs=200L;repeated.coarseEndMs=500L;
        lesson.sentences=List.of(first,repeated);
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).containsExactly(
            java.util.Map.of("text","We learn. Learn.","start_ms",0L,"end_ms",500L));
        assertThatThrownBy(()->LessonAuthoringService.validateTiming(lesson)).hasMessageContaining("no observed timing");
        assertThatThrownBy(()->service.karaoke(lesson.id,5)).hasMessageContaining("complete transcript");
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,first.text,null),
            new LessonAuthoringService.SentenceInput(repeated.id,repeated.text,null,true)));
        assertThat(lesson.sentences.getLast().hidden).isFalse();
        assertThat(lesson.sentences.getLast().usage).isEqualTo("WATCH_ONLY");
        assertThat(lesson.timingStatus).isEqualTo("STALE");
        assertThat(lesson.approvedRevision).isNull();assertThat(lesson.karaokeReviewedRevision).isNull();
        verifyNoInteractions(jobs);
    }
    @Test void restoringHiddenInvalidTimingRequiresAlignmentAndPreservesAudio() {
        lesson.sourceType="TEXT";lesson.audioObjectKey="speech.wav";
        var first=lesson.sentences.getFirst();
        var repeated=new AuthoringSentence(UUID.randomUUID(),"Learn.","LEARN");
        repeated.hidden=true;repeated.coarseStartMs=200L;repeated.coarseEndMs=500L;
        lesson.sentences=List.of(first,repeated);lesson.timingStatus="VALID";
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,first.text,null),
            new LessonAuthoringService.SentenceInput(repeated.id,repeated.text,null,false)));
        assertThat(lesson.sentences.getLast().hidden).isFalse();
        assertThat(lesson.timingStatus).isEqualTo("STALE");
        assertThat(lesson.audioObjectKey).isEqualTo("speech.wav");
    }
    @Test void splittingInvalidRawTimingKeepsSafeSourceAnchorsForRealignment() {
        var first=lesson.sentences.getFirst();
        first.words=List.of(new AuthoringSentence.Word("We",300,400,0,2,0,1),
            new AuthoringSentence.Word("learn",200,500,3,8,1,2));
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,"We!",null),
            new LessonAuthoringService.SentenceInput(null,"Learn!",null)));
        assertThat(lesson.timingStatus).isEqualTo("STALE");
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).hasSize(1);
        assertThat(lesson.sentences.getLast().coarseEndMs).isEqualTo(500L);
    }
    @Test void lexicalEditAndSplitRetainOneObservedAlignmentWindow() {
        var first=lesson.sentences.getFirst();
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,"We!","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"Teach!","LEARN")));
        assertThat(lesson.timingStatus).isEqualTo("STALE");
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).containsExactly(
            java.util.Map.of("text","We! Teach!","start_ms",0L,"end_ms",500L));
        assertThat(lesson.sentences).allSatisfy(s->assertThat(s.words).isEmpty());
    }
    @Test void lexicalEditAndMergeRetainBothObservedBoundaries() {
        var first=lesson.sentences.getFirst();
        var second=new AuthoringSentence(UUID.randomUUID(),"Practice helps.","LEARN");
        second.coarseStartMs=600L;second.coarseEndMs=900L;
        lesson.sentences=List.of(first,second);
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(first.id,"We teach. Practice helps.","LEARN")));
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).containsExactly(
            java.util.Map.of("text","We teach. Practice helps.","start_ms",0L,"end_ms",900L));
    }
    @Test void sttWindowSeamInsideSentenceUsesOriginalCompleteWindows() {
        lesson.endMs=230_000;
        lesson.sentences=AuthoringText.split("We learn every day. Practice helps.");
        List<java.util.Map<String,Object>> segments=List.of(java.util.Map.of("text","We learn every","start_ms",0L,"end_ms",115_000L),
            java.util.Map.of("text","day. Practice helps.","start_ms",115_000L,"end_ms",230_000L));
        lesson.timingEvidence=java.util.Map.of("segments",segments);
        AuthoringText.seedCoarse(lesson.sentences,lesson.timingEvidence);
        assertThat(lesson.sentences.getFirst().coarseEndMs).isEqualTo(230_000L);
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).containsExactlyElementsOf(segments);
        lesson.sentences.getFirst().text="We teach every day.";
        assertThatThrownBy(()->LessonAuthoringService.alignmentSegments(lesson)).hasMessageContaining("safe alignment window");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void longSentenceLexicalEditThenSplitRecoversObservedDescendantWindows(boolean saveEditFirst) {
        lesson.endMs=120_000;
        var original=new AuthoringSentence(UUID.randomUUID(),"We learn every day and practice together.","LEARN");
        AuthoringText.mapTiming(List.of(original),List.of(
            new AuthoringText.ObservedWord("We",0,10_000),
            new AuthoringText.ObservedWord("learn",10_000,20_000),
            new AuthoringText.ObservedWord("every",20_000,30_000),
            new AuthoringText.ObservedWord("day",30_000,60_000),
            new AuthoringText.ObservedWord("and",60_000,70_000),
            new AuthoringText.ObservedWord("practice",70_000,90_000),
            new AuthoringText.ObservedWord("together",90_000,120_000)),120_000);
        lesson.sentences=List.of(original);
        if(saveEditFirst) service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,
            "We teach every day and practice together.","LEARN")));
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,
            "We teach every day.","LEARN"),new LessonAuthoringService.SentenceInput(null,"And practice together.","LEARN")));
        assertThat(lesson.timingStatus).isEqualTo("STALE");
        assertThat(lesson.sentences).allSatisfy(sentence->assertThat(sentence.words).isEmpty());
        assertThat(LessonAuthoringService.alignmentSegments(lesson)).containsExactly(
            java.util.Map.of("text","We teach every day.","start_ms",0L,"end_ms",60_000L),
            java.util.Map.of("text","And practice together.","start_ms",60_000L,"end_ms",120_000L));
    }
    @Test void splitWithAmbiguousObservedBoundaryRequiresRecoveryInsteadOfDuplicatingParentRange() {
        lesson.endMs=120_000;
        var original=new AuthoringSentence(UUID.randomUUID(),"We learn and we learn and we learn.","LEARN");
        AuthoringText.mapTiming(List.of(original),List.of(
            new AuthoringText.ObservedWord("We learn",0,20_000),new AuthoringText.ObservedWord("and",20_000,40_000),
            new AuthoringText.ObservedWord("we learn",40_000,60_000),new AuthoringText.ObservedWord("and",60_000,80_000),
            new AuthoringText.ObservedWord("we learn",80_000,120_000)),120_000);
        lesson.sentences=List.of(original);
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,"We teach and we learn.","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"And we learn.","LEARN")));
        assertThatThrownBy(()->LessonAuthoringService.alignmentSegments(lesson)).hasMessageContaining("No safe ASR");
        assertThat(lesson.sentences).allSatisfy(sentence->assertThat(sentence.coarseStartMs).isNull());
    }
    @Test void insertionAtLongSplitBoundaryDoesNotInventATimestamp() {
        lesson.endMs=120_000;
        var original=new AuthoringSentence(UUID.randomUUID(),"We learn and practice.","LEARN");
        AuthoringText.mapTiming(List.of(original),List.of(new AuthoringText.ObservedWord("We",0,20_000),
            new AuthoringText.ObservedWord("learn",20_000,60_000),new AuthoringText.ObservedWord("and",60_000,80_000),
            new AuthoringText.ObservedWord("practice",80_000,120_000)),120_000);
        lesson.sentences=List.of(original);
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,"We learn today.","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"And practice.","LEARN")));
        assertThatThrownBy(()->LessonAuthoringService.alignmentSegments(lesson)).hasMessageContaining("create a new draft").hasMessageNotContaining("re-prepare");
        assertThat(lesson.sentences).allSatisfy(sentence->{
            assertThat(sentence.words).isEmpty();assertThat(sentence.coarseStartMs).isNull();
        });
    }
    @Test void repeatedBoundaryInsideGroupedObservedWordRemainsAmbiguous() {
        lesson.endMs=120_000;
        var original=new AuthoringSentence(UUID.randomUUID(),"We learn and practice and practice.","LEARN");
        AuthoringText.mapTiming(List.of(original),List.of(new AuthoringText.ObservedWord("We",0,10_000),
            new AuthoringText.ObservedWord("learn",10_000,20_000),new AuthoringText.ObservedWord("and practice",20_000,60_000),
            new AuthoringText.ObservedWord("and",60_000,80_000),new AuthoringText.ObservedWord("practice",80_000,120_000)),120_000);
        lesson.sentences=List.of(original);
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,"We teach and practice and.","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"Practice.","LEARN")));
        assertThatThrownBy(()->LessonAuthoringService.alignmentSegments(lesson)).hasMessageContaining("No safe ASR");
    }
    @Test void legacyJsonDefaultsToEmptyObservedSourceEvidence() {
        var old=new tools.jackson.databind.ObjectMapper().readValue("{\"text\":\"We learn.\"}",AuthoringSentence.class);
        assertThat(old.sourceWords).isEmpty();
    }
    @Test void olderDraftWithoutObservedEvidenceDoesNotAcquireInventedAnchors() {
        lesson.endMs=120_000;
        var original=lesson.sentences.getFirst();
        original.words=List.of();original.coarseStartMs=0L;original.coarseEndMs=120_000L;
        service.transcript(lesson.id,5,List.of(new LessonAuthoringService.SentenceInput(original.id,"We!","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"Teach!","LEARN")));
        assertThatThrownBy(()->LessonAuthoringService.alignmentSegments(lesson)).hasMessageContaining("No safe ASR");
    }
    @Test void rejectsStaleAndPublishedWritesAndIneligibleSelection() {
        assertThatThrownBy(()->service.approve(lesson.id,4)).hasMessageContaining("refresh");
        lesson.sentences.getFirst().dictationEligible=false;
        lesson.publishedAt=Instant.now();
        assertThatThrownBy(()->service.approve(lesson.id,5)).hasMessageContaining("read-only");
    }
}
