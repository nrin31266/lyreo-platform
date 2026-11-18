package com.lyreo.toeic;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.toeic.application.*;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.assessment.api.*;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.*;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ToeicQuestionEditorTest {
    final ToeicEditor tests=mock(ToeicEditor.class);final AssessmentLibrary questions=mock(AssessmentLibrary.class);final AssessmentEditorial ai=mock(AssessmentEditorial.class);
    final ToeicQuestionEditor editor=new ToeicQuestionEditor(tests,questions,ai);
    final UUID id=UUID.randomUUID(),placement=UUID.randomUUID(),item=UUID.randomUUID(),group=UUID.randomUUID();
    ToeicDraft fixture(int part) {
        var content=new ToeicDraft("Test","TEST","FULL_TEST",null,null,2700,4500,"PUBLIC",null,
            List.of(new ToeicDraft.Group(group,part,"TEXT","Saved group","Current transcript","Bản dịch",null,List.of(new ToeicDraft.Document(UUID.randomUUID(),1,"PASSAGE","<p>Edited passage</p>")),List.of(),false)),
            List.of(new ToeicDraft.Placement(placement,item,null,group,part,151,null)));
        when(tests.get(id)).thenReturn(new DocumentStore.Document<>(id,3,"PUBLISHED",UUID.randomUUID(),content,null,"admin",false));return content;
    }
    QuestionContent question(List<QuestionContent.Media> media) {return new QuestionContent("Choose", "MCQ_SINGLE",List.of(new QuestionContent.Option("A","a"),new QuestionContent.Option("B","b"),new QuestionContent.Option("C","c"),new QuestionContent.Option("D","d")),"A",1,null,null,null,null,null,media);}
    @Test void staleTestAndInvalidReadingMediaCannotChangeTheSharedQuestion() {
        fixture(7);var q=question(List.of());
        assertThatThrownBy(()->editor.save(id,placement,2,1,q,"admin")).isInstanceOf(StateConflictException.class);
        assertThatThrownBy(()->editor.draft(id,placement,3,1,question(List.of(new QuestionContent.Media(UUID.randomUUID(),"audio"))),"admin")).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(questions);
    }
    @ParameterizedTest @ValueSource(strings={"ABD","ACD"})
    void partTwoRequiresExactlyAbcBeforeSavingOrPublishingAQuestion(String keys) {
        fixture(2);var q=question(List.of());
        var options=keys.chars().mapToObj(key->new QuestionContent.Option(String.valueOf((char)key),"Answer")).toList();
        var invalid=new QuestionContent(q.title(),q.kind(),options,"A",q.difficultyLevel(),null,null,null,null,null,List.of());
        assertThatThrownBy(()->editor.draft(id,placement,3,1,invalid,"admin")).isInstanceOf(RequestValidationException.class).hasMessageContaining("A, B and C");
        assertThatThrownBy(()->editor.save(id,placement,3,1,invalid,"admin")).isInstanceOf(RequestValidationException.class).hasMessageContaining("A, B and C");
        verifyNoInteractions(questions);
    }
    @Test void savingPinsOnlyTheSelectedPlacementAndDoesNotPublishTheTest() {
        fixture(7);var q=question(List.of());var publication=UUID.randomUUID();
        when(questions.save(item,1,q,"admin")).thenReturn(new DocumentStore.Document<>(item,2,"DRAFT",null,q,null,"admin"));
        when(questions.publish(item,2,null,"admin")).thenReturn(new DocumentStore.Document<>(item,3,"PUBLISHED",publication,q,null,"admin",false));
        editor.save(id,placement,3,1,q,"admin");
        verify(tests).save(eq(id),eq(3L),argThat(value->publication.equals(value.placements().getFirst().publicationId())),eq("admin"));
        verify(tests,never()).publish(any(),anyLong(),any(),any());
    }
    @Test void aiUsesCurrentSavedGroupAndPlacementContext() {
        var test=fixture(7);editor.suggest(id,placement,3,1,"Translate","admin");
        verify(ai).suggest(eq(item),eq(1L),eq("Translate"),eq("admin"),argThat(context->{var c=(Map<?,?>)context;return c.get("group").equals(test.groups().getFirst()) && c.get("placementId").equals(placement.toString()) && c.get("testRevision").equals(3L);}));
    }
    AiDraftRequests.Request suggestion(UUID request,Map<String,Object> context) {
        return new AiDraftRequests.Request(request,"assessment",item,1,null,"SUGGEST",Map.of("sourceContext",context),Map.of(),Map.of(),UUID.randomUUID());
    }
    @Test void missingOrUnsupportedAcceptanceKindCannotReachTheSharedDraft() {
        fixture(7);var request=UUID.randomUUID();
        when(ai.request(request)).thenReturn(suggestion(request,Map.of("testId",id.toString(),"placementId",placement.toString(),"testRevision",3)));
        for(String kind:Arrays.asList(null,"","candidate","audio"))
            assertThatThrownBy(()->editor.accept(id,placement,request,3,1,kind,0,"admin")).isInstanceOf(RequestValidationException.class);
        verify(ai,never()).accept(any(),any(),anyLong(),any(),anyInt(),any());
        verifyNoInteractions(questions);
    }
    @Test void staleTestOrDifferentPlacementContextCannotReachTheSharedDraft() {
        fixture(7);var request=UUID.randomUUID();
        for(var context:List.<Map<String,Object>>of(
            Map.of("testId",id.toString(),"placementId",placement.toString(),"testRevision",2),
            Map.of("testId",UUID.randomUUID().toString(),"placementId",placement.toString(),"testRevision",3),
            Map.of("testId",id.toString(),"placementId",UUID.randomUUID().toString(),"testRevision",3))) {
            when(ai.request(request)).thenReturn(suggestion(request,context));
            assertThatThrownBy(()->editor.accept(id,placement,request,3,1,"suggestion",0,"admin")).isInstanceOf(StateConflictException.class);
        }
        verify(ai,never()).accept(any(),any(),anyLong(),any(),anyInt(),any());
        verifyNoInteractions(questions);
    }
}
