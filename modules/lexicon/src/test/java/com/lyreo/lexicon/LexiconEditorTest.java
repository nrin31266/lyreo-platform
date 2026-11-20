package com.lyreo.lexicon;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lyreo.lexicon.api.LexiconController;
import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.application.port.LexiconEditorSource;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.lexicon.infrastructure.persistence.JdbcLexiconRepository;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class LexiconEditorTest {
    @Test
    void publicationAllowsOnlyUnchangedSourceStubsAndStillRequiresAReadableMeaning() {
        var documents=mock(DocumentStore.class);
        var source=mock(LexiconEditorSource.class);
        var editor=new LexiconEditor(documents,source,mock(AssetStore.class));
        var id=UUID.randomUUID();
        var item=new LexiconDraft.Item(UUID.randomUUID(),"noun",null,null);
        var meaning=new LexiconDraft.Sense(UUID.randomUUID(),item.id(),"Printed pages",null,"MISSING",List.of(),List.of(),List.of(),null);
        var stub=new LexiconDraft.Sense(UUID.randomUUID(),item.id(),null,null,"MISSING",List.of(),List.of(),List.of("no-gloss"),null);
        var editedStub=new LexiconDraft.Sense(stub.id(),item.id(),null,null,"MISSING",List.of(),List.of(Map.of("text","New example")),stub.tags(),null);
        var imported=draft(item,List.of(meaning,stub));
        when(source.source(id)).thenReturn(Optional.of(imported));
        var published=new DocumentStore.Document<>(id,3,"PUBLISHED",UUID.randomUUID(),imported,null,"admin");
        when(documents.publish("lexicon",id,2,null,"admin",LexiconDraft.class)).thenReturn(published);
        var draft=new AtomicReference<>(imported);
        when(documents.find("lexicon",id,LexiconDraft.class)).thenAnswer(ignored -> Optional.of(
            new DocumentStore.Document<>(id,2,"PUBLISHED",null,draft.get(),null,"admin")));

        assertThat(editor.publish(id,2,null,"admin")).isEqualTo(published);
        draft.set(draft(item,List.of(meaning,editedStub)));
        assertThatThrownBy(() -> editor.publish(id,2,null,"admin")).hasMessageContaining("definition");
        draft.set(draft(item,List.of(stub)));
        assertThatThrownBy(() -> editor.publish(id,2,null,"admin")).hasMessageContaining("readable meaning");
        verify(documents,times(1)).publish("lexicon",id,2,null,"admin",LexiconDraft.class);
    }

    private LexiconDraft draft(LexiconDraft.Item item,List<LexiconDraft.Sense> senses) {
        return new LexiconDraft("book","WORD",null,null,null,
            List.of(item),senses,List.of(),List.of(),List.of());
    }

    static Stream<Arguments> definitions() {
        return Stream.of(
            Arguments.of(null,"Sách",Map.of("vi","Sách")),
            Arguments.of("  ","Sách",Map.of("vi","Sách")),
            Arguments.of("Printed pages",null,Map.of("en","Printed pages")),
            Arguments.of("Printed pages","Sách",Map.of("en","Printed pages","vi","Sách")));
    }

    @ParameterizedTest @MethodSource("definitions")
    void creatingAndPublishingAFormSenseExposesItsLanguageDefinitionsToLearners(String english,String vietnamese,Map<String,String> expected) {
        var json=new ObjectMapper();var documents=mock(DocumentStore.class);var source=mock(LexiconEditorSource.class);
        var editor=new LexiconEditor(documents,source,mock(AssetStore.class));
        UUID item=UUID.randomUUID(),sense=UUID.randomUUID(),headword=UUID.randomUUID();
        var fields=new HashMap<String,Object>();fields.put("id",sense);fields.put("itemId",item);
        fields.put("definitionEn",english);fields.put("translationVi",vietnamese);
        fields.put("translationStatus",vietnamese==null?"MISSING":"NEEDS_REVIEW");
        fields.put("rawGlosses",List.of());fields.put("examples",List.of());fields.put("tags",List.of());
        var input=json.convertValue(Map.of("title","new-word","type","WORD","headwordId",headword,
            "items",List.of(Map.of("id",item,"partOfSpeech","noun")),"senses",List.of(fields),
            "forms",List.of(),"pronunciations",List.of(),"translations",List.of()),LexiconDraft.class);
        assertThat(input.senses().getFirst().definitions()).isNull();
        UUID entry=UUID.randomUUID();
        when(source.source(entry)).thenReturn(Optional.of(input));
        when(documents.source(entry,input)).thenReturn(new DocumentStore.Document<>(entry,0,"PUBLISHED",null,input,null,null));
        var stored=new AtomicReference<DocumentStore.Document<LexiconDraft>>();
        when(documents.save(eq("lexicon"),any(),eq(0L),any(LexiconDraft.class),eq("admin"),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=new DocumentStore.Document<LexiconDraft>(call.getArgument(1),1,"DRAFT",null,call.getArgument(3),null,"admin");stored.set(row);return row;});
        when(documents.find(eq("lexicon"),any(),eq(LexiconDraft.class))).thenAnswer(call->Optional.ofNullable(stored.get()));
        when(documents.publish(eq("lexicon"),any(),eq(1L),isNull(),eq("admin"),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=stored.get();var published=new DocumentStore.Document<>(row.id(),2,"PUBLISHED",UUID.randomUUID(),row.draft(),null,"admin",false);stored.set(published);return published;});
        when(documents.published(eq("lexicon"),any(),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=stored.get();return Optional.of(new DocumentStore.Publication<>(row.publicationId(),1,row.draft(),null,null,"admin"));});

        var created=editor.save(entry,0,input,"admin");
        var published=editor.publish(created.id(),created.revision(),null,"admin");
        var learner=new LexiconController(new LexiconSearchService(new JdbcLexiconRepository(null,documents,json)),json).get(created.id());

        assertThat(published.draft().headwordId()).isEqualTo(headword);
        assertThat(learner.canonicalForm()).isEqualTo("new-word");
        assertThat(learner.senses().getFirst().definitions()).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(learner.senses().getFirst().definitionEn()).isEqualTo(english);
        assertThat(learner.senses().getFirst().translationVi()).isEqualTo(vietnamese);
        verify(source).indexPublication(created.id(),published.draft());
    }
}
