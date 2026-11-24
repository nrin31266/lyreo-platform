package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import com.lyreo.assessment.infrastructure.persistence.JdbcQuestionSource;
import com.lyreo.grammar.infrastructure.persistence.JdbcGrammarCatalogSource;
import com.lyreo.toeic.infrastructure.persistence.JdbcToeicEditorSource;
import com.lyreo.lexicon.infrastructure.persistence.JdbcLexiconEditorSource;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.infrastructure.persistence.JdbcVocabularyCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=LibraryPaginationIT.Config.class)
@Transactional
class LibraryPaginationIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({DocumentStore.class,JdbcQuestionSource.class,JdbcGrammarCatalogSource.class,JdbcToeicEditorSource.class,JdbcLexiconEditorSource.class,JdbcVocabularyCatalog.class})
    static class Config {}
    @Autowired DocumentStore documents;
    @Autowired JdbcQuestionSource questions;
    @Autowired JdbcGrammarCatalogSource grammar;
    @Autowired JdbcToeicEditorSource toeic;
    @Autowired JdbcLexiconEditorSource lexicon;
    @Autowired JdbcTemplate jdbc;
    @Autowired VocabularyCatalog vocabulary;

    @Test
    void vocabularyEditsKeepSourceIdentityAndRejectOutdatedEditors() {
        UUID release = UUID.randomUUID();
        UUID pack = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        UUID audio = UUID.randomUUID(), image = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json)
            VALUES(?,'vocabulary','test','1.0.0',?,'{}'::jsonb)
            """, release, "a".repeat(64));
        jdbc.update("""
            INSERT INTO vocabulary_pack(id,release_id,source_topic,position,title_en,title_vi)
            VALUES(?,?,'daily',1,'Daily','Hằng ngày')
            """, pack, release);
        jdbc.update("INSERT INTO vocabulary_unit(id,pack_id,position,level,title) VALUES(?,?,1,'A1','A1 · 01')", unit, pack);
        jdbc.update("""
            INSERT INTO content_asset(id,sha256,storage_key,mime_type,size_bytes,filename,created_by)
            VALUES(?,?,'media/audio','audio/mpeg',20,'example.mp3','test')
            """, audio, "b".repeat(64));
        jdbc.update("""
            INSERT INTO content_asset(id,sha256,storage_key,mime_type,size_bytes,filename,created_by)
            VALUES(?,?,'media/image','image/webp',20,'book.webp','test')
            """, image, "c".repeat(64));
        jdbc.update("""
            INSERT INTO vocabulary_unit_entry(id,unit_id,position,source_id,term,content,image_id,example_audio_id)
            VALUES(?,?,1,'book','book',?::jsonb,?,?)
            """, entry, unit, """
            {"ipaUk":"/bʊk/","ipaUs":"/bʊk/","senses":[{"pos":"n.","definitionEn":"Printed pages"}],
             "examples":["Open the book.","She found a book."],"collocations":[],"family":[],"synonyms":[],"antonyms":[]}
            """, image, audio);

        assertThat(vocabulary.packs()).singleElement().satisfies(row -> {
            assertThat(row.entryCount()).isEqualTo(1);
            assertThat(row.unitCount()).isEqualTo(1);
            assertThat(row.coverImage()).isEqualTo(image);
        });
        assertThat(vocabulary.units(pack)).extracting(VocabularyCatalog.Unit::id).containsExactly(unit);
        assertThat(vocabulary.entries(null, null, "PAGES", 0).items())
            .extracting(VocabularyCatalog.EntrySummary::id).containsExactly(entry);
        assertThat(vocabulary.entries(pack, unit, "%", 0).total()).isZero();
        assertThat(vocabulary.entries(pack, unit, "book", 1).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "book", 1).items()).isEmpty();
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.MISSING_VI, 0).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.MISSING_SYNONYMS, 0).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.MISSING_ANTONYMS, 0).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.NEEDS_REVIEW, 0).total()).isZero();
        var original = vocabulary.entry(entry);
        assertThat(original.media().exampleAudio()).isEqualTo(audio);

        var edited = new VocabularyCatalog.LearningContent(original.content().ipaUk(), original.content().ipaUs(),
            List.of(new VocabularyCatalog.Sense("n.", "Printed pages", "sách")), List.of("Read the book.", "She found a book."),
            List.of(), List.of(), List.of(), List.of(), null, null, true, List.of());
        var save = new VocabularyCatalog.EntryEdit(0L, edited, true);
        vocabulary.saveEntry(entry, save);
        assertThat(vocabulary.entry(entry)).satisfies(row -> {
            assertThat(row.term()).isEqualTo("book");
            assertThat(row.revision()).isEqualTo(1);
            assertThat(row.hidden()).isTrue();
            assertThat(row.content().needsReview()).isTrue();
            assertThat(row.media().exampleAudio()).isNull();
        });
        assertThat(vocabulary.entries(pack, unit, "sách", 0).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.MISSING_VI, 0).total()).isZero();
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.NEEDS_REVIEW, 0).total()).isEqualTo(1);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.HIDDEN, 0).total()).isEqualTo(1);
        assertThatThrownBy(() -> vocabulary.saveEntry(entry, save)).isInstanceOf(StateConflictException.class);
        vocabulary.savePack(pack, new VocabularyCatalog.PackEdit(0L, "Daily life", "Đời sống", true));
        vocabulary.saveUnit(unit, new VocabularyCatalog.UnitEdit(0L, "Daily basics", false, null));
        assertThat(vocabulary.packs()).extracting(VocabularyCatalog.Pack::titleVi).containsExactly("Đời sống");
        assertThat(vocabulary.units(pack)).extracting(VocabularyCatalog.Unit::title).containsExactly("Daily basics");
        jdbc.update("UPDATE vocabulary_unit_entry SET hidden=false WHERE id=?", entry);
        assertThat(vocabulary.entries(pack, unit, "", VocabularyCatalog.Review.HIDDEN, 0).items())
            .extracting(VocabularyCatalog.EntrySummary::hidden).containsExactly(true);
        vocabulary.saveUnit(unit, new VocabularyCatalog.UnitEdit(1L, "Daily basics", false, "A2"));
        assertThat(vocabulary.units(pack)).extracting(VocabularyCatalog.Unit::level).containsExactly("A2");
    }


    @Test void filteredTotalsMatchAllPagesIncludingAnEmptyLastPage() {
        UUID parent=UUID.randomUUID();
        for(int i=0;i<65;i++) {
            documents.save("assessment",UUID.randomUUID(),0,Map.of("title","Question "+i,"difficultyLevel",i%2+1),"admin",Map.class);
            documents.save("grammar-subtopic",UUID.randomUUID(),0,Map.of("title","Topic "+i,"parentId",parent,"accessMode",i%2==0?"PUBLIC":"FEATURE","questions",List.of(),"orderIndex",i),"admin",Map.class);
            documents.save("toeic",UUID.randomUUID(),0,Map.of("title","Test "+i,"year",2026,"type","FULL_TEST","accessMode","PUBLIC","placements",List.of(),"groups",List.of()),"admin",Map.class);
        }
        assertThat(questions.count("Question",1,"DRAFT")).isEqualTo(33);
        var first=questions.list("Question",1,"DRAFT",0,30);
        var last=questions.list("Question",1,"DRAFT",1,30);
        assertThat(first).hasSize(30);assertThat(last).hasSize(3);
        assertThat(first).extracting(row->row.id()).doesNotContainAnyElementsOf(last.stream().map(row->row.id()).toList());
        assertThat(questions.list("Question",1,"DRAFT",2,30)).isEmpty();
        assertThat(questions.count("Missing",1,"DRAFT")).isZero();
        assertThat(grammar.count("subtopic","Topic",parent,"DRAFT","PUBLIC")).isEqualTo(33);
        assertThat(grammar.list("subtopic","Topic",parent,"DRAFT","PUBLIC",1,30)).hasSize(3);
        assertThat(grammar.count("subtopic","Topic",UUID.randomUUID(),"DRAFT","PUBLIC")).isZero();
        assertThat(toeic.count("Test",2026,"DRAFT","FULL_TEST","PUBLIC","")).isEqualTo(65);
        assertThat(toeic.list("Test",2026,"DRAFT","FULL_TEST","PUBLIC","",2,30)).hasSize(5);
        assertThat(toeic.count("Test",2025,"DRAFT","FULL_TEST","PUBLIC","")).isZero();
    }

    @Test void catalogReportsFailedJobsInsteadOfLeavingThemRunning() {
        UUID target=UUID.randomUUID(),job=UUID.randomUUID();
        documents.save("toeic",target,0,Map.of("title","Failed quality","type","FULL_TEST","accessMode","PUBLIC","placements",List.of(),"groups",List.of()),"admin",Map.class);
        jdbc.update("INSERT INTO background_job(id,job_type,owner_module,status) VALUES(?,'TOEIC_QUALITY','toeic','FAILED')",job);
        jdbc.update("INSERT INTO content_quality_report(id,namespace,target_id,revision,fingerprint,rules_version,job_id,context) VALUES(?,'toeic',?,1,?,?,?,'{}'::jsonb)",UUID.randomUUID(),target,"a".repeat(64),com.lyreo.platform.content.QualityReports.RULES_VERSION,job);
        assertThat(toeic.list("Failed quality",null,"","FULL_TEST","","FAILED",0,30)).extracting(row->row.qualityStatus()).containsExactly("FAILED");
        assertThat(toeic.count("Failed quality",null,"","FULL_TEST","","RUNNING")).isZero();
    }

    @Test void lexiconPrefixFormPosAndCursorRetainAllMatchesWithoutDuplicates() {
        UUID release=UUID.randomUUID();
        jdbc.update("INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json) VALUES(?,'lexicon','pagination','1.0.0',?,CAST('{}' AS jsonb))",release,"d".repeat(64));
        for(int i=0;i<4;i++) {
            UUID entry=UUID.randomUUID(),headword=UUID.randomUUID(),item=UUID.randomUUID();
            String title=i==0?"Book":i==1?"bookish":i==2?"volume":"booking";
            jdbc.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en',?)",headword,title);
            jdbc.update("INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,display_form,lookup_form,entry_type) VALUES(?,?,?,?,?,?,'WORD')",entry,release,headword,UUID.randomUUID(),title,title.toLowerCase(Locale.ROOT));
            jdbc.update("INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,part_of_speech,pos_title,order_index) VALUES(?,?,?,?,?,'',0)",item,release,entry,UUID.randomUUID(),i==3?"verb":"noun");
            if(i==0) {
                jdbc.update("INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,position,definition_en,translation_status) VALUES(?,?,?,?,?,0,'Printed pages','MISSING')",UUID.randomUUID(),release,entry,item,UUID.randomUUID());
                jdbc.update("INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,position,translation_vi,translation_status) VALUES(?,?,?,?,?,1,'sach','VERIFIED')",UUID.randomUUID(),release,entry,item,UUID.randomUUID());
            }
            if(i==2) jdbc.update("INSERT INTO lexicon_form(id,release_id,entry_id,item_id,package_form_id,form,normalized_form) VALUES(?,?,?,?,?,'books','books')",UUID.randomUUID(),release,entry,item,UUID.randomUUID());
        }
        jdbc.update("INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at) VALUES(?,?,'APPLY','SUCCEEDED',now())",UUID.randomUUID(),release);
        jdbc.update("INSERT INTO dataset_active_release(domain,release_id,activated_by) VALUES('lexicon',?,'test')",release);
        var first=lexicon.list("Book","WORD","","","noun",null,2);
        assertThat(first).extracting(row->row.title()).containsExactly("Book","bookish");
        assertThat(first.getFirst().translationVi()).isEqualTo("sach");
        assertThat(lexicon.count("Book","WORD","","","noun")).isEqualTo(3);
        assertThat(lexicon.browse("Book","WORD","","","noun",null,null,true,0,1)).extracting(row->row.title()).containsExactly("volume");
        assertThat(lexicon.browse("Book","WORD","","","noun",null,null,true,1,1)).extracting(row->row.title()).containsExactly("bookish");
        assertThat(lexicon.browse("Book","WORD","","","noun",null,first.getLast().cursor(),false,0,1)).extracting(row->row.title()).containsExactly("Book");
        var next=lexicon.list("Book","WORD","","","noun",first.getLast().cursor(),2);
        assertThat(next).extracting(row->row.title()).containsExactly("volume");
        assertThat(lexicon.list("Book","WORD","","","noun",next.getLast().cursor(),2)).isEmpty();
        assertThat(lexicon.list("Book","WORD","","","verb",null,30)).extracting(row->row.title()).containsExactly("booking");
        assertThat(lexicon.count("", "WORD", "VERIFIED", "PUBLISHED", "noun")).isEqualTo(1);
        assertThat(lexicon.browse("", "WORD", "VERIFIED", "PUBLISHED", "noun", null, null, true, 0, 1))
            .extracting(row -> row.title()).containsExactly("Book");

        var sourceEntry = first.getFirst().id();
        documents.save("lexicon",sourceEntry,0,Map.of(
            "title","Book","type","WORD","items",List.of(Map.of("partOfSpeech","verb")),
            "senses",List.of(Map.of("translationStatus","VERIFIED","translationVi","dat truoc"))),"admin",Map.class);
        assertThat(lexicon.count("", "", "VERIFIED", "", "noun")).isZero();
        assertThat(lexicon.count("", "", "VERIFIED", "PUBLISHED", "verb")).isEqualTo(1);
        assertThat(lexicon.list("", "", "VERIFIED", "PUBLISHED", "verb", null, 30))
            .extracting(row -> row.translationVi()).containsExactly("dat truoc");
    }
    @Test void adminFindsNewAndEditedDraftFormsBeforePublication() {
        UUID id=UUID.randomUUID();
        var senses=List.of(Map.of("translationVi","di"),Map.of("definitionEn","Move from one place to another"));
        documents.save("lexicon",id,0,Map.of("title","go","type","WORD","forms",List.of(Map.of("form","went")),"items",List.of(),"senses",senses),"admin",Map.class);
        assertThat(lexicon.count("went","","","","" )).isEqualTo(1);
        assertThat(lexicon.list("went","","","","",null,30)).extracting(row->row.id()).containsExactly(id);
        assertThat(lexicon.list("went","","","","",null,30).getFirst().definition()).isEqualTo("Move from one place to another");
        assertThat(lexicon.list("wen","","","","",null,30)).extracting(row->row.title()).containsExactly("go");
    }

}
