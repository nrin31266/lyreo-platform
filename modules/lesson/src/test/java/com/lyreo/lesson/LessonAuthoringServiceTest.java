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
