package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lyreo.lesson.application.authoring.LessonAuthoringService;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TextAuthoringTest {
    final AuthoringRepository repository=mock(AuthoringRepository.class);
    final BackgroundJobService jobs=mock(BackgroundJobService.class);
    final LessonAuthoringService service=new LessonAuthoringService(repository,mock(AuthoringSourcePort.class),jobs);

    @Test void fullTextSourceIsSplitOnCoreAndInvalidatesSpeechWithoutCallingAi() {
        var draft=service.createText("Lesson","We learn.","bf_emma");draft.audioObjectKey="old.wav";draft.endMs=1000;
        when(repository.find(draft.id,true)).thenReturn(Optional.of(draft));
        service.text(draft.id,draft.revision,"We practise. Every day helps.");
        assertThat(draft.sentences).extracting(row->row.text).containsExactly("We practise.","Every day helps.");
        assertThat(draft.audioObjectKey).isNull();assertThat(draft.ttsVoice).isEqualTo("bf_emma");
        verifyNoInteractions(jobs);
    }
    @Test void transcriptWithoutUsagePreservesLegacyFieldsAndCanBeApproved() {
        var draft=service.createText("Lesson","We learn.","af_heart");
        draft.audioObjectKey="audio.wav";draft.endMs=1000;
        var sentence=draft.sentences.getFirst();sentence.usage="WATCH_ONLY";sentence.translation="Chúng ta học.";
        when(repository.find(draft.id,true)).thenReturn(Optional.of(draft));
        service.transcript(draft.id,draft.revision,List.of(new LessonAuthoringService.SentenceInput(sentence.id,"We learn!",null)));
        assertThat(draft.sentences.getFirst().usage).isEqualTo("WATCH_ONLY");
        assertThat(draft.sentences.getFirst().translation).isEqualTo("Chúng ta học.");
        draft.audioObjectKey="fresh.wav";draft.endMs=1000;
        assertThat(service.approve(draft.id,draft.revision).approvedRevision).isEqualTo(draft.transcriptRevision);
    }

    @Test void blankTitleUsesFirstSentenceAndMarksItAutomatic() {
        var draft=service.createText(null,"  Every day is a new chance. We learn together.","af_heart");
        assertThat(draft.title).isEqualTo("Every day is a new chance.");
        assertThat(draft.automaticTitle).isTrue();
        assertThat(service.createText("My title","We learn.","af_heart").automaticTitle).isFalse();
    }
    @Test void automaticTitleIsBoundedWithoutSplittingUnicodeCharacters() {
        String unicode=LessonAuthoringService.automaticTitle("Hello "+"😀".repeat(150));
        assertThat(unicode.codePointCount(0,unicode.length())).isEqualTo(120);
        assertThat(unicode).endsWith("😀…");
        String ascii=LessonAuthoringService.automaticTitle("a".repeat(200));
        assertThat(ascii).hasSize(120).endsWith("…");
    }
    @Test void savingTextCreatesAReviewableDraftWithoutStartingAi() {
        var draft=service.createText("Lesson","We learn. Practice helps.","af_heart");
        assertThat(draft.sourceType).isEqualTo("TEXT");
        assertThat(draft.sentences).hasSize(2);
        assertThat(draft.endMs).isZero();
        verify(repository).create(draft);
        verifyNoInteractions(jobs);
    }

    @Test void editingTextInvalidatesAudioAndAllTimingEvenForPunctuation() {
        var draft=new AuthoringLesson();draft.id=UUID.randomUUID();draft.sourceType="TEXT";
        draft.revision=2;draft.audioObjectKey="old.wav";draft.endMs=1000;
        var sentence=new AuthoringSentence(UUID.randomUUID(),"We learn.","LEARN");
        draft.sentences=List.of(sentence);
        when(repository.find(draft.id,true)).thenReturn(Optional.of(draft));
        service.transcript(draft.id,2,List.of(new LessonAuthoringService.SentenceInput(sentence.id,"We learn!","LEARN")));
        assertThat(draft.audioObjectKey).isNull();assertThat(draft.endMs).isZero();
        assertThat(draft.timingStatus).isEqualTo("MISSING");
        assertThatThrownBy(()->service.approve(draft.id,2)).hasMessageContaining("audio");
    }

    @Test void voiceChangeKeepsTextAndRejectsStaleRevision() {
        var draft=service.createText("Lesson","We learn.","af_heart");
        draft.revision=3;draft.audioObjectKey="old.wav";draft.endMs=1000;
        when(repository.find(draft.id,true)).thenReturn(Optional.of(draft));
        assertThatThrownBy(()->service.voice(draft.id,2,"bf_emma")).hasMessageContaining("Draft changed");
        service.voice(draft.id,3,"bf_emma");
        assertThat(draft.ttsVoice).isEqualTo("bf_emma");
        assertThat(draft.sentences.getFirst().text).isEqualTo("We learn.");
        assertThat(draft.audioObjectKey).isNull();
    }

    @Test void rejectsMoreSentencesThanTheSharedEditorSupports() {
        assertThatThrownBy(()->service.createText("Lesson","Go. ".repeat(1001),"af_heart")).hasMessageContaining("1000 sentences");
        verifyNoInteractions(repository,jobs);
    }

    @Test void rejectsOversizedTextWithoutPersisting() {
        assertThatThrownBy(()->service.createText("Lesson","a".repeat(10001),"af_heart"))
            .hasMessageContaining("10000");
        verifyNoInteractions(repository,jobs);
    }
}
