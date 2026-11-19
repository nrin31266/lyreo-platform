package com.lyreo.lexicon;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.lexicon.domain.*;
import com.lyreo.lexicon.infrastructure.persistence.LexiconDraftMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LexiconEditorialTest {
    final UUID noun=UUID.randomUUID(),verb=UUID.randomUUID(),sense=UUID.randomUUID();
    LexiconDraft draft(List<LexiconDraft.Translation> translations,List<LexiconDraft.Sound> sounds) {
        return new LexiconDraft("book","WORD",null,null,null,
            List.of(new LexiconDraft.Item(noun,"noun",null,null),new LexiconDraft.Item(verb,"verb",null,null)),
            List.of(new LexiconDraft.Sense(sense,noun,"Printed pages",null,"MISSING",List.of(),List.of(),List.of(),null)),List.of(),sounds,translations);
    }
    @Test void rejectsTranslationLinkedToASenseInAnotherItem() {
        var translation=new LexiconDraft.Translation(UUID.randomUUID(),verb,sense,"sách","source","item",null,"DIRECT_SENSE","noun");
        assertThatThrownBy(()->LexiconEditor.validate(draft(List.of(translation),List.of()),false))
            .isInstanceOf(RequestValidationException.class);
    }
    @Test void rejectsDuplicateRecordingIdentities() {
        var recording=new LexiconDraft.Sound(UUID.randomUUID(),noun,"US","/bʊk/",null,null,List.of());
        assertThatThrownBy(()->LexiconEditor.validate(draft(List.of(),List.of(recording,recording)),false))
            .isInstanceOf(RequestValidationException.class);
    }
    @Test void aDraftRoundTripPreservesRecordingAndTranslationProvenance() {
        var entry=new LexiconEntry(UUID.randomUUID(),UUID.randomUUID(),"book","book",LexiconEntry.EntryType.WORD,"en",null,null,
            List.of(new LexiconEntry.LexiconItem(noun,"noun",null,2,"Etymology",0)),List.of(),List.of(),
            List.of(new LexiconEntry.Pronunciation(UUID.randomUUID(),noun,"US","/bʊk/","book.ogg","https://example.test/book.ogg","https://example.test/source","source/audio","[\"regional\"]")),
            List.of(new LexiconEntry.LexiconTranslation(UUID.randomUUID(),noun,null,"sách","source","item","printed pages","ITEM_CANDIDATE","ambiguous","[\"literary\"]","noun",Map.of("sense_ordinal",2))));
        var mapper=new LexiconDraftMapper(new ObjectMapper());
        var result=mapper.entry(entry.id(),mapper.from(entry));
        assertThat(result.items()).isEqualTo(entry.items());
        assertThat(result.pronunciations()).isEqualTo(entry.pronunciations());
        assertThat(result.translations()).isEqualTo(entry.translations());
    }
}
