package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.lyreo.ai.application.execution.*;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.lesson.application.authoring.*;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.domain.authoring.*;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthoringContentGeneratorTest {
    final AiInvocationService ai=mock(AiInvocationService.class);
    final AuthoringRepository repository=mock(AuthoringRepository.class);
    final AuthoringJobCommitter committer=mock(AuthoringJobCommitter.class);
    final BackgroundJob job=mock(BackgroundJob.class);
    final JobExecutionContext context=mock(JobExecutionContext.class);
    final AuthoringContentGenerator generator=new AuthoringContentGenerator(ai,repository,committer);
    final AuthoringLesson lesson=new AuthoringLesson();
    final AuthoringRepository.JobInput input=new AuthoringRepository.JobInput(UUID.randomUUID(),"CONTENT",1,false);
    AuthoringContentGeneratorTest() {
        when(ai.requestTokenBudget(any())).thenReturn(7500);
        lesson.options=new AuthoringOptions(Set.of(),Set.of("TRANSLATION","LEXICAL","GRAMMAR"),"vi-VN","AUTO",20,8,"DISABLED","US","LOCAL");
        lesson.sentences=List.of(new AuthoringSentence(UUID.randomUUID(),"She gave up smoking.","LEARN"),
            new AuthoringSentence(UUID.randomUUID(),"She feels better now.","LEARN"));
    }
    AiExecutionResult result(Map<String,Object> output) {return new AiExecutionResult(UUID.randomUUID(),"GEMINI","test",output,100,100,10);}
    @Test void singletonObjectArrayIsNormalizedWithoutAnotherModelRequest() {
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("content",
            "[{\"tr\":[[1,\"Một\"],[2,\"Hai\"]],\"v\":[],\"g\":[]}]")));
        generator.generate(job,input,lesson,context);
        assertThat(AuthoringContent.complete(lesson)).isTrue();
        assertThat(lesson.sentences).extracting(s->s.translation).containsExactly("Một","Hai");
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"[{},{ }]", "[{", "null", "[42]"})
    void malformedOrAmbiguousJsonNeverCreatesAContentCheckpoint(String raw) {
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("content",raw)));
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).isInstanceOf(com.lyreo.platform.jobs.application.PermanentJobException.class);
        verifyNoInteractions(committer);
    }
    @Test void hundredSentencesUseBatchesAndDoNotRepeatTranscriptInInput() {
        lesson.options=AuthoringOptions.defaults();
        lesson.sentences=java.util.stream.IntStream.range(0,100).mapToObj(i->new AuthoringSentence(UUID.randomUUID(),"We learn English together every day because practice helps us understand people around the world.","LEARN")).toList();
        var calls=new ArrayList<String>();
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap())).thenAnswer(call->{
            String prompt=call.getArgument(1);calls.add(prompt);
            assertThat((Map<?,?>)call.getArgument(2)).isEmpty();
            var rows=new ArrayList<List<Object>>();
            for(String line:prompt.split("\n")) if(line.matches("\\d+\\|.*")) rows.add(List.of(Integer.parseInt(line.substring(0,line.indexOf('|'))),"Chúng tôi học cùng nhau."));
            return result(Map.of("structured",Map.of("tr",rows,"v",List.of(),"g",List.of(),"d",List.of(),"s",List.of(),"m",List.of("A2","MONOLOGUE"))));
        });
        when(ai.executeForProvider(eq(AiCapability.NLP),anyString(),anyString(),anyMap(),anyMap()))
            .thenReturn(result(Map.of("items",List.of(Map.of("i",1,"ipa","reference","needs_review",false)),"version","test")));
        generator.generate(job,input,lesson,context);
        assertThat(calls).hasSizeBetween(2,20);
        assertThat(lesson.sentences).allSatisfy(s->{assertThat(s.translation).isNotBlank();assertThat(s.ipa).isNotBlank();});
        assertThat(AuthoringContent.complete(lesson)).isTrue();
        int initial=calls.size();generator.generate(job,input,lesson,context);
        assertThat(calls).hasSize(initial);
    }
    @Test void missingTranslationKeepsValidCheckpointAndDoesNotCallRepair() {
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(1,"Dịch")),"v",List.of(),"g",List.of()))));
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).hasMessageContaining("missing");
        assertThat(lesson.sentences.getFirst().translation).isEqualTo("Dịch");verify(committer).contentCheckpoint(eq(job),eq(input),eq(lesson),anyList(),anySet());
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
    }
    @Test void validExtraNotesAreBoundedLocallyWithoutDiscardingTranslations() {
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of(
            "tr",List.of(List.of(1,"Một"),List.of(2,"Hai")),
            "v",List.of(List.of(1,"gave up","từ bỏ"),List.of(2,"feels better","khỏe hơn")),"g",List.of()))));
        generator.generate(job,input,lesson,context);
        assertThat(lesson.sentences.getFirst().vocabulary).hasSize(1);
        assertThat(lesson.sentences.getLast().vocabulary).isEmpty();
        assertThat(lesson.sentences.getLast().translation).isEqualTo("Hai");
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
    }
    @Test void truncationSplitsOnlyFailedBatch() {
        when(ai.execute(any(),anyString(),anyMap(),anyMap()))
            .thenThrow(new AiExecutionException("truncated",false,"AI_OUTPUT_INCOMPLETE"))
            .thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(1,"Một")),"v",List.of(),"g",List.of()))))
            .thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(2,"Hai")),"v",List.of(),"g",List.of()))));
        generator.generate(job,input,lesson,context);
        verify(committer,times(2)).contentCheckpoint(eq(job),eq(input),eq(lesson),anyList(),anySet());
        assertThat(AuthoringContent.complete(lesson)).isTrue();
    }
    @Test void ipaFailureKeepsLlmCheckpointAndRetryDoesNotCallLlmAgain() {
        lesson.options=AuthoringOptions.defaults();
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(1,"Một"),List.of(2,"Hai")),"v",List.of(),"g",List.of(),"d",List.of(),"s",List.of(),"m",List.of("A2","MONOLOGUE")))));
        when(ai.executeForProvider(any(),anyString(),anyString(),anyMap(),anyMap())).thenThrow(new AiExecutionException("unavailable",true,"AI_HTTP_503"));
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).isInstanceOf(com.lyreo.platform.jobs.application.PermanentJobException.class);
        verify(committer).contentCheckpoint(eq(job),eq(input),eq(lesson),eq(List.of(0,1)),anySet());
        assertThat(lesson.sentences.getFirst().translation).isEqualTo("Một");
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).isInstanceOf(com.lyreo.platform.jobs.application.PermanentJobException.class);
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
    }
    @Test void badAnnotationAndProfileDoNotDiscardTranslationsAndRetryOnlyMissingProfile() {
        lesson.options=new AuthoringOptions(Set.of("SHADOWING"),Set.of("TRANSLATION","LEXICAL","PROFILE"),"vi-VN","AUTO",20,8,"DISABLED","US");
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(1,"Một"),List.of(2,"Hai")),"v",List.of(List.of(1,"gave ... smoking","bad")),"s",List.of(),"m",List.of("bad","bad")))))
            .thenReturn(result(Map.of("structured",Map.of("m",List.of("A2","MONOLOGUE")))));
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).hasMessageContaining("incomplete");
        assertThat(lesson.sentences.getFirst().translation).isEqualTo("Một");
        assertThat(lesson.sentences.getFirst().vocabulary).isEmpty();
        assertThat(lesson.sentences.getFirst().contentWarnings).isNotEmpty();
        generator.generate(job,input,lesson,context);
        verify(ai).execute(any(),argThat(prompt->prompt.contains("m:[") && !prompt.contains("tr:[[")),anyMap(),anyMap());
    }
    @Test void defaultSelectionKeepsSimpleSentencesAndOnlyExcludesGroundedReasons() {
        lesson.options=new AuthoringOptions(Set.of("DICTATION","SHADOWING"),Set.of(),"vi-VN","AUTO",20,8,"DISABLED","US");
        var texts=List.of("I don't agree.","It's too cold.","Lightning can be scary.","My wife likes the rain, but only when she.","Subscribe now!");
        lesson.sentences=texts.stream().map(text->{var s=new AuthoringSentence(UUID.randomUUID(),text,"LEARN");s.words=List.of(new AuthoringSentence.Word(text,0,100,0,text.length(),0,AuthoringText.tokens(text).size()));return s;}).toList();
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of("d",List.of(List.of(5,"PROMOTION")),"s",List.of(List.of(4,"BROKEN_FRAGMENT"),List.of(5,"PROMOTION"))))));
        generator.generate(job,input,lesson,context);
        assertThat(lesson.sentences.subList(0,3)).allSatisfy(s->{assertThat(s.dictationSelected).isTrue();assertThat(s.shadowingSelected).isTrue();});
        assertThat(lesson.sentences.get(3).exclusions).containsEntry("SHADOWING","BROKEN_FRAGMENT");
    }
    @Test void disabledFeaturesDoNotRunAndManualTranslationIsPreserved() {
        lesson.options=new AuthoringOptions(Set.of(),Set.of("TRANSLATION"),"vi-VN","AUTO",20,8,"DISABLED","US","LOCAL");
        var first=lesson.sentences.getFirst();first.translation="Bản sửa tay";first.manualFields.add("TRANSLATION");first.contentKeys.put("TRANSLATION",AuthoringContent.key(lesson,0,"TRANSLATION"));
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("structured",Map.of("tr",List.of(List.of(2,"Hai"))))));
        generator.generate(job,input,lesson,context);
        assertThat(first.translation).isEqualTo("Bản sửa tay");
        verify(ai,never()).executeForProvider(any(),anyString(),anyString(),anyMap(),anyMap());
        verify(ai).execute(any(),argThat(prompt->!prompt.contains("1|")),anyMap(),anyMap());
    }
    @Test void cancellationAfterInferencePreventsCheckpoint() {
        when(ai.execute(any(),anyString(),anyMap(),anyMap())).thenAnswer(call->{
            doThrow(new java.util.concurrent.CancellationException()).when(context).throwIfCancellationRequested();
            return result(Map.of("structured",Map.of()));
        });
        assertThatThrownBy(()->generator.generate(job,input,lesson,context)).isInstanceOf(java.util.concurrent.CancellationException.class);
        verifyNoInteractions(committer);
    }
}
