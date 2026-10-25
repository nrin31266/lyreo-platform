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
    private final AuthoringRepository repository=mock(AuthoringRepository.class);
    private final BackgroundJobService jobs=mock(BackgroundJobService.class);
    private final LessonAuthoringService service=new LessonAuthoringService(repository,mock(AuthoringSourcePort.class),jobs);
    private AuthoringLesson lesson;
    @BeforeEach void setup() {
        lesson=new AuthoringLesson();lesson.id=UUID.randomUUID();lesson.revision=5;lesson.transcriptRevision=2;
        lesson.endMs=1000;lesson.timingStatus="DRAFT_VALID";lesson.status="CONTENT_REVIEW";
        var sentence=new AuthoringSentence(UUID.randomUUID(),"We learn.","LEARN");
        AuthoringText.mapTiming(List.of(sentence),List.of(new AuthoringText.ObservedWord("We",0,200),new AuthoringText.ObservedWord("learn",200,500)),1000);
        sentence.dictationEligible=true;sentence.shadowingEligible=true;
        lesson.sentences=List.of(sentence);lesson.approvedRevision=2L;lesson.karaokeReviewedRevision=2L;lesson.semanticRevision=2L;
        when(repository.find(lesson.id,true)).thenReturn(Optional.of(lesson));
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
    @Test void semanticRequiresExplicitKaraokeAndCurrentApproval() {
        lesson.karaokeReviewedRevision=null;
        assertThatThrownBy(()->service.generate(lesson.id,5)).hasMessageContaining("karaoke");
        verifyNoInteractions(jobs);
    }
    @Test void candidateOnlyEditKeepsApprovalAndDoesNotCallAi() {
        service.candidates(lesson.id,5,List.of(lesson.sentences.getFirst().id),List.of());
        assertThat(lesson.approvedRevision).isEqualTo(2L);assertThat(lesson.semanticRevision).isEqualTo(2L);
        verifyNoInteractions(jobs);
    }
    @Test void activeRegenerationCannotSilentlyOverwriteCandidateEdits() {
        lesson.activeJobId=UUID.randomUUID();
        var job=mock(com.lyreo.platform.jobs.domain.BackgroundJob.class);
        when(job.status()).thenReturn(com.lyreo.platform.jobs.domain.BackgroundJobStatus.RUNNING);
        when(jobs.findById(lesson.activeJobId)).thenReturn(Optional.of(job));
        assertThatThrownBy(()->service.candidates(lesson.id,5,List.of(),List.of())).hasMessageContaining("active authoring job");
    }
    @Test void rejectsStaleAndPublishedWritesAndIneligibleSelection() {
        assertThatThrownBy(()->service.approve(lesson.id,4)).hasMessageContaining("refresh");
        lesson.sentences.getFirst().dictationEligible=false;
        assertThatThrownBy(()->service.candidates(lesson.id,5,List.of(lesson.sentences.getFirst().id),List.of())).hasMessageContaining("Ineligible");
        lesson.publishedAt=Instant.now();
        assertThatThrownBy(()->service.approve(lesson.id,5)).hasMessageContaining("read-only");
    }
}
