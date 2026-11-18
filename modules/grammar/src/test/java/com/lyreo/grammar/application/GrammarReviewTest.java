package com.lyreo.grammar.application;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.contracts.errors.RequestValidationException;
import java.util.*;
import org.junit.jupiter.api.Test;
class GrammarReviewTest {
    final GrammarCatalogSource source=mock(GrammarCatalogSource.class);
    final DocumentStore documents=mock(DocumentStore.class);
    final AssessmentLibrary items=mock(AssessmentLibrary.class);
    final GrammarCatalogEditor editor=new GrammarCatalogEditor(documents,source,items);
    @Test void queueReportsOnlyCurrentStructuralIssuesAndRecheckingClearsFixedContent() {
        var parent=UUID.randomUUID();var sub=UUID.randomUUID();var bank=UUID.randomUUID();
        when(source.list(eq("topic"),anyString(),isNull(),anyString(),anyString(),eq(0),eq(100))).thenReturn(List.of(new GrammarCatalogSource.Summary(parent,"Tenses","topic",null,"HIDDEN","PUBLIC",3)));
        when(source.list(eq("subtopic"),anyString(),isNull(),anyString(),anyString(),eq(0),eq(100))).thenReturn(List.of(new GrammarCatalogSource.Summary(sub,"Present","subtopic",parent,"PUBLISHED","PUBLIC",3)));
        when(source.list(eq("bank"),anyString(),isNull(),anyString(),anyString(),eq(0),eq(100))).thenReturn(List.of(new GrammarCatalogSource.Summary(bank,"Bank","bank",null,"DRAFT","PUBLIC",0)));
        when(source.find("topic",parent)).thenReturn(Optional.of(new GrammarCatalog("Tenses","Tenses",null,null,null,0,"PUBLIC",null,List.of())));
        when(source.find("subtopic",sub)).thenReturn(Optional.of(new GrammarCatalog("Present","Present",null,null,parent,0,"PUBLIC",null,List.of())));
        when(source.find("bank",bank)).thenReturn(Optional.of(new GrammarCatalog("Bank","Bank",null,null,null,0,"PUBLIC",null,List.of())));
        assertThat(editor.review()).extracting(GrammarCatalogEditor.ReviewIssue::code).containsExactly("HIDDEN_PARENT","EMPTY_BANK");
        when(source.list(eq("topic"),anyString(),isNull(),anyString(),anyString(),eq(0),eq(100))).thenReturn(List.of(new GrammarCatalogSource.Summary(parent,"Tenses","topic",null,"PUBLISHED","PUBLIC",3)));
        when(source.list(eq("bank"),anyString(),isNull(),anyString(),anyString(),eq(0),eq(100))).thenReturn(List.of(new GrammarCatalogSource.Summary(bank,"Bank","bank",null,"DRAFT","PUBLIC",3)));
        assertThat(editor.review()).extracting(GrammarCatalogEditor.ReviewIssue::code).doesNotContain("HIDDEN_PARENT");verifyNoInteractions(items);
    }
    @Test void questionChecksUseThePinnedRevisionAndDoNotPublishOrResolveSourceReports() {
        var id=UUID.randomUUID();var item=UUID.randomUUID();var revision=UUID.randomUUID();
        var content=new GrammarCatalog("Bank","Bank",null,null,null,0,"PUBLIC",null,List.of(new GrammarCatalog.QuestionRef(item,revision)));
        when(documents.find("grammar-bank",id,GrammarCatalog.class)).thenReturn(Optional.of(new DocumentStore.Document<>(id,1,"DRAFT",null,content,null,"admin")));
        doThrow(new RequestValidationException("needs passage")).when(items).validateStandalone(item,revision);
        assertThat(editor.check("bank",id)).containsExactly(new GrammarCatalogEditor.CheckIssue("INVALID_QUESTION",item,1,"ERROR","needs passage"));
        verify(items).validateStandalone(item,revision);verify(documents,never()).publish(any(),any(),anyLong(),any(),any(),any());
    }
    @Test void restoreRevalidatesPinnedQuestionContextBeforeApplyingOldContent() {
        var id=UUID.randomUUID();var item=UUID.randomUUID();var questionRevision=UUID.randomUUID();var publication=UUID.randomUUID();
        var content=new GrammarCatalog("Bank","Bank",null,null,null,0,"PUBLIC",null,List.of(new GrammarCatalog.QuestionRef(item,questionRevision)));
        when(documents.publication("grammar-bank",id,publication,GrammarCatalog.class)).thenReturn(new DocumentStore.Publication<>(publication,2,content,null,null,"admin"));
        doThrow(new RequestValidationException("Question requires its passage")).when(items).validateStandalone(item,questionRevision);
        assertThatThrownBy(()->editor.restore("bank",id,5,publication,"admin")).isInstanceOf(RequestValidationException.class).hasMessageContaining("passage");
        verify(documents,never()).restore(any(),any(),anyLong(),any(),any(),any());
    }

    @Test void invalidCatalogChecksKeepTheSpecificRecoveryMessage() {
        var id=UUID.randomUUID();
        var content=new GrammarCatalog("Bank","Bank",null,null,null,0,"PUBLIC",null,List.of());
        when(documents.find("grammar-bank",id,GrammarCatalog.class)).thenReturn(Optional.of(new DocumentStore.Document<>(id,1,"DRAFT",null,content,null,"admin")));
        assertThat(editor.check("bank",id)).extracting(GrammarCatalogEditor.CheckIssue::message).containsExactly("Add questions before publishing a bank");
    }
}
