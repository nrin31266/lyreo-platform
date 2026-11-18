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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class LexiconEditorTest {
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
        var input=json.convertValue(Map.of("title","  new-word  ","type","WORD",
            "items",List.of(Map.of("id",item,"partOfSpeech","noun")),"senses",List.of(fields),
            "forms",List.of(),"pronunciations",List.of(),"translations",List.of()),LexiconDraft.class);
        assertThat(input.senses().getFirst().definitions()).isNull();
        when(source.headword("new-word")).thenReturn(headword);
        var stored=new AtomicReference<DocumentStore.Document<LexiconDraft>>();
        when(documents.save(eq("lexicon"),any(),eq(0L),any(LexiconDraft.class),eq("admin"),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=new DocumentStore.Document<LexiconDraft>(call.getArgument(1),1,"DRAFT",null,call.getArgument(3),null,"admin");stored.set(row);return row;});
        when(documents.find(eq("lexicon"),any(),eq(LexiconDraft.class))).thenAnswer(call->Optional.ofNullable(stored.get()));
        when(documents.publish(eq("lexicon"),any(),eq(1L),isNull(),eq("admin"),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=stored.get();var published=new DocumentStore.Document<>(row.id(),2,"PUBLISHED",UUID.randomUUID(),row.draft(),null,"admin",false);stored.set(published);return published;});
        when(documents.published(eq("lexicon"),any(),eq(LexiconDraft.class)))
            .thenAnswer(call->{var row=stored.get();return Optional.of(new DocumentStore.Publication<>(row.publicationId(),1,row.draft(),null,null,"admin"));});

        var created=editor.create(input,"admin");
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
