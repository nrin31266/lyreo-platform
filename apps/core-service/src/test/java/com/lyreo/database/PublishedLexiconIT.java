package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.lexicon.api.LexiconController;
import com.lyreo.lexicon.application.LexiconEditor;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.lexicon.infrastructure.persistence.*;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=PublishedLexiconIT.Config.class)
class PublishedLexiconIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({DocumentStore.class,LexiconEditor.class,JdbcLexiconEditorSource.class})
    static class Config {
        @Bean com.lyreo.platform.content.AssetStore assets() {return org.mockito.Mockito.mock(com.lyreo.platform.content.AssetStore.class);}
        @Bean JdbcLexiconRepository learner(NamedParameterJdbcTemplate jdbc,DocumentStore documents,ObjectMapper json) {return new JdbcLexiconRepository(jdbc,documents,json);}
    }
    @Autowired LexiconEditor editor;
    @Autowired JdbcLexiconRepository learner;
    @Autowired DocumentStore documents;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void onlyPublishedContentAndFormsReachLearnersAndHeadwordIdentitySurvivesRestore() {
        UUID item=UUID.randomUUID(),sense=UUID.randomUUID(),headword=UUID.randomUUID();
        jdbc.getJdbcTemplate().update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en','LyreoWord')",headword);
        var input=new LexiconDraft("LyreoWord","WORD",headword,null,null,List.of(new LexiconDraft.Item(item,"noun",null,null)),List.of(new LexiconDraft.Sense(sense,item,"A test word","Từ thử","VERIFIED",List.of(),List.of(),List.of(),null)),List.of(new LexiconDraft.Form(UUID.randomUUID(),item,"LyreoWords",List.of())),List.of(),List.of());
        var draft=documents.save("lexicon",UUID.randomUUID(),0,input.withProvenanceFrom(input),"admin",LexiconDraft.class);
        assertThat(learner.findById(draft.id())).isEmpty();
        assertThat(learner.search("LyreoWords","lyreowords",20).results()).isEmpty();
        var published=editor.publish(draft.id(),draft.revision(),"Reviewed","admin");
        assertThat(learner.search("LyreoWords","lyreowords",20).results()).extracting(hit->hit.entryId()).containsExactly(draft.id());
        assertThat(learner.exactHeadwordIds("lyreowords")).containsExactly(published.draft().headwordId());
        var d=published.draft();
        var changed=editor.save(draft.id(),published.revision(),new LexiconDraft(d.title(),d.type(),d.headwordId(),d.sourceMetadataJson(),d.licenseText(),d.items(),d.senses(),List.of(),d.pronunciations(),d.translations()),"admin");
        var latest=editor.publish(draft.id(),changed.revision(),null,"admin");
        var hidden=documents.status("lexicon",draft.id(),latest.revision(),"HIDDEN","admin",LexiconDraft.class);
        assertThat(learner.findById(draft.id())).isEmpty();
        assertThat(learner.search("LyreoWord","lyreoword",20).results()).isEmpty();
        var restored=editor.restore(draft.id(),hidden.revision(),published.publicationId(),"admin");
        assertThat(restored.draft().headwordId()).isEqualTo(published.draft().headwordId());
        assertThat(learner.findById(draft.id())).isEmpty();
        documents.status("lexicon",draft.id(),restored.revision(),"PUBLISHED","admin",LexiconDraft.class);
        assertThat(learner.findById(draft.id()).orElseThrow().headwordId()).isEqualTo(published.draft().headwordId());
        assertThat(restored.publicationId()).isNotEqualTo(published.publicationId());
        assertThat(documents.audit("lexicon",draft.id())).hasSize(8);
    }
    @Test void historicalSparseDraftsRecoverSourceProvenanceAndPublicationDoesNotExposeAdminContext() {
        var sql=jdbc.getJdbcTemplate();
        UUID release=UUID.randomUUID(),entry=UUID.randomUUID(),headword=UUID.randomUUID(),item=UUID.randomUUID(),sense=UUID.randomUUID(),recording=UUID.randomUUID(),translation=UUID.randomUUID();
        sql.update("INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json) VALUES(?,'lexicon',?,'1.0.0',?,CAST('{}' AS jsonb))",release,release.toString().replace("-",""),"a".repeat(64));
        sql.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en','ProvenanceBook')",headword);
        sql.update("INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,display_form,lookup_form,entry_type) VALUES(?,?,?,?,'ProvenanceBook','provenancebook','WORD')",entry,release,headword,UUID.randomUUID());
        sql.update("INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,part_of_speech,pos_title,etymology_number,order_index) VALUES(?,?,?,?,'noun','Noun',2,0)",item,release,entry,UUID.randomUUID());
        sql.update("INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,position,definition_en,translation_status) VALUES(?,?,?,?,?,0,'Printed pages','MISSING')",sense,release,entry,item,UUID.randomUUID());
        sql.update("INSERT INTO lexicon_pronunciation(id,release_id,entry_id,item_id,package_pronunciation_id,accent,ipa,audio_file,audio_url,source_url,cached_audio_object_key,tags) VALUES(?,?,?,?,?,'US','/bʊk/','book.ogg','https://example.test/book.ogg','https://example.test/source','source/audio',CAST('[\"regional\"]' AS jsonb))",recording,release,entry,item,UUID.randomUUID());
        sql.update("INSERT INTO lexicon_translation(id,release_id,entry_id,item_id,package_translation_id,word_vi,source,source_scope,link_status,unlinked_reason,tags,source_pos,source_context) VALUES(?,?,?,?,?,'sách','Dictionary','item','ITEM_CANDIDATE','ambiguous',CAST('[\"literary\"]' AS jsonb),'noun',CAST('{\"record_line\":42}' AS jsonb))",translation,release,entry,item,UUID.randomUUID());
        sql.update("INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at) VALUES(?,?,'APPLY','SUCCEEDED',now())",UUID.randomUUID(),release);
        sql.update("INSERT INTO dataset_active_release(domain,release_id,activated_by) VALUES('lexicon',?,'test') ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id",release);
        var source=editor.get(entry).draft();
        var sound=source.pronunciations().getFirst();var candidate=source.translations().getFirst();
        var sparse=new LexiconDraft(source.title(),source.type(),source.headwordId(),source.sourceMetadataJson(),source.licenseText(),List.of(new LexiconDraft.Item(item,"noun","Noun",null)),source.senses(),source.forms(),
            List.of(new LexiconDraft.Sound(recording,item,sound.accent(),sound.ipa(),sound.audioUrl(),null,sound.tags())),
            List.of(new LexiconDraft.Translation(translation,item,null,candidate.wordVi(),candidate.source(),candidate.sourceScope(),null,candidate.linkStatus(),candidate.sourcePos())));
        documents.save("lexicon",entry,0,sparse,"legacy",LexiconDraft.class);
        var hydrated=editor.get(entry);
        assertThat(hydrated.draft().items().getFirst().etymologyNumber()).isEqualTo(2);
        assertThat(hydrated.draft().translations().getFirst().sourceContext()).containsEntry("record_line",42);
        assertThat(hydrated.draft().translations().getFirst().tags()).containsExactly("literary");
        assertThat(hydrated.draft().pronunciations().getFirst().sourceUrl()).isEqualTo("https://example.test/source");
        var saved=editor.save(entry,hydrated.revision(),hydrated.draft(),"admin");
        var published=editor.publish(entry,saved.revision(),null,"admin");
        assertThat(published.draft().translations().getFirst().sourceContext()).containsEntry("record_line",42);
        var visible=learner.findById(entry).orElseThrow();
        assertThat(visible.pronunciations().getFirst().audioFile()).isEqualTo("book.ogg");
        assertThat(visible.translations().getFirst().unlinkedReason()).isEqualTo("ambiguous");
        var response = new LexiconController(new LexiconSearchService(learner), json).get(entry);
        assertThat(json.writeValueAsString(response.translations())).doesNotContain("sourceContext", "record_line");
    }

}
