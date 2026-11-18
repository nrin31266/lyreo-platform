package com.lyreo.assessment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.assessment.application.port.QuestionSource;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.platform.content.DocumentStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssessmentLibraryTest {
    DocumentStore documents=mock(DocumentStore.class);
    QuestionSource source=mock(QuestionSource.class);
    AssessmentLibrary library=new AssessmentLibrary(documents,source);
    QuestionContent question(String answer) {
        return new QuestionContent("Choose", "GRAMMAR",List.of(new QuestionContent.Option("A","First"),new QuestionContent.Option("B","Second")),answer,1,null,null,null,null,null,List.of());
    }

    @Test void sourceReferencesKeepOriginalAnswerAfterSharedItemIsEdited() {
        UUID id=UUID.randomUUID();var original=question("A");
        when(source.find(id)).thenReturn(Optional.of(original));
        assertThat(library.published(id,null)).isEqualTo(original);
        assertThat(library.pin(id,null)).isNull();
        verifyNoInteractions(documents);
    }

    @Test void newQuestionsPinAnImmutablePublication() {
        UUID id=UUID.randomUUID(),revision=UUID.randomUUID();
        when(source.find(id)).thenReturn(Optional.empty());
        when(documents.published("assessment",id,QuestionContent.class)).thenReturn(Optional.of(
            new DocumentStore.Publication<>(revision,4,question("B"),"Reviewed",Instant.now(),"admin")));
        assertThat(library.pin(id,null)).isEqualTo(revision);
        when(documents.publication("assessment",id,revision,QuestionContent.class)).thenReturn(
            new DocumentStore.Publication<>(revision,4,question("B"),"Reviewed",Instant.now(),"admin"));
        assertThat(library.published(id,revision).correctOption()).isEqualTo("B");
    }

    @Test void malformedOptionsAndAnswersAreRejectedAtTheBoundary() {
        var invalid=new QuestionContent("Choose","GRAMMAR",Arrays.asList((QuestionContent.Option)null),"A",1,null,null,null,null,null,List.of());
        assertThatThrownBy(()->AssessmentLibrary.validate(invalid,true)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->AssessmentLibrary.validate(question("C"),true)).isInstanceOf(RequestValidationException.class);
    }
    @Test void aClozeItemCanPublishOptionsWithoutInventingAStandaloneStem() {
        var q=question("A");
        var cloze=new QuestionContent(null,"CLOZE",q.options(),"A",1,null,null,null,null,null,List.of());
        assertThatCode(()->AssessmentLibrary.validate(cloze,true)).doesNotThrowAnyException();
        var empty=new QuestionContent(null,"MCQ_SINGLE",q.options(),"A",1,null,null,null,null,null,List.of());
        assertThatThrownBy(()->AssessmentLibrary.validate(empty,true)).isInstanceOf(RequestValidationException.class);
    }
    @Test void groupedSourceQuestionsCannotPublishInStandaloneGrammarBanks() {
        UUID id=UUID.randomUUID();when(source.find(id)).thenReturn(Optional.of(question("A")));
        when(source.requiresContext(id)).thenReturn(true);
        assertThatThrownBy(()->library.validateStandalone(id,null)).isInstanceOf(RequestValidationException.class).hasMessageContaining("passage/audio");
        when(source.requiresContext(id)).thenReturn(false);
        assertThatCode(()->library.validateStandalone(id,null)).doesNotThrowAnyException();
    }
}
