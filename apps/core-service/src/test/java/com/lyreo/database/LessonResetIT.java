package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class LessonResetIT {
    @Test void resetIsAtomicAndPreservesBanksConfigurationHistoryAndSharedMedia() throws Exception {
        try(var postgres=new PostgreSQLContainer("postgres:18.6-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").target("010").load().migrate();
            try(var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var sql=connection.createStatement()) {
                sql.execute("INSERT INTO lesson(id,title,source_type,status) VALUES('10000000-0000-0000-0000-000000000001','Old title','TEXT','DRAFT')");
                Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();
                assertThat(count(sql,"lesson")).isEqualTo(1);
                String seed=Files.readString(Path.of("../../infra/postgres/seed/dev.sql"));sql.execute(seed);
                sql.execute("UPDATE lesson_category SET name='Custom travel',translations='{\"vi\":\"Tự sửa\"}' WHERE id='20000000-0000-0000-0000-000000000002'");
                sql.execute(seed);assertThat(count(sql,"lesson_category")).isEqualTo(24);
                try(var result=sql.executeQuery("SELECT name,translations->>'vi' FROM lesson_category WHERE id='20000000-0000-0000-0000-000000000002'")) {
                    result.next();assertThat(result.getString(1)).isEqualTo("Custom travel");assertThat(result.getString(2)).isEqualTo("Tự sửa");
                }
                sql.execute("""
                    INSERT INTO app_user(id,keycloak_subject) VALUES('10000000-0000-0000-0000-000000000002','reset-user');
                    INSERT INTO lesson_progress(learner_id,lesson_id) VALUES('10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001');
                    INSERT INTO speech_attempt(id,learner_id,lesson_id,recording_object_key) VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','lesson.wav');
                    INSERT INTO speech_attempt(id,learner_id,recording_object_key) VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000002','unrelated.wav');
                    INSERT INTO lesson_audio_source(id,object_key,content_type,duration_ms) VALUES(gen_random_uuid(),'cached.wav','audio/wav',1000);
                    INSERT INTO lesson_youtube_channel(channel_id,title) VALUES('UCaaaaaaaaaaaaaaaaaaaaaa','Saved channel');
                    INSERT INTO background_job(id,job_type,owner_module,owner_reference_id,status)
                      VALUES('10000000-0000-0000-0000-000000000003','LESSON_AUTHORING','lesson','10000000-0000-0000-0000-000000000001','RUNNING'),
                            (gen_random_uuid(),'VOICE_SAMPLE_PREPARE','lesson',gen_random_uuid(),'SUCCEEDED');
                    INSERT INTO lesson_authoring(lesson_id,source_start_ms,active_job_id)
                      VALUES('10000000-0000-0000-0000-000000000001',0,'10000000-0000-0000-0000-000000000003');
                    INSERT INTO lesson_authoring_job(job_id,lesson_id,operation,transcript_revision)
                      VALUES('10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001','PREPARE',0);
                    INSERT INTO lesson_job_diagnostic(id,job_id,payload)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000003','{"output":"private diagnostic"}');
                    INSERT INTO lesson_sentence(id,lesson_id,position,text,audio_start_ms,audio_end_ms)
                      VALUES('10000000-0000-0000-0000-000000000009','10000000-0000-0000-0000-000000000001',0,'We learn.',0,1000);
                    INSERT INTO lesson_word_timestamp(id,sentence_id,position,surface_text,start_ms,end_ms)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000009',0,'We',0,200);
                    INSERT INTO lesson_annotation(id,lesson_id,sentence_id,annotation_type,payload_json)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000009','TRANSLATION','{}');
                    INSERT INTO lesson_activity(id,lesson_id,activity_type,position)
                      VALUES('10000000-0000-0000-0000-000000000010','10000000-0000-0000-0000-000000000001','DICTATION',0);
                    INSERT INTO lesson_activity_sentence(activity_id,sentence_id)
                      VALUES('10000000-0000-0000-0000-000000000010','10000000-0000-0000-0000-000000000009');
                    UPDATE lesson SET status='PUBLISHED' WHERE id='10000000-0000-0000-0000-000000000001';
                    INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json)
                      VALUES('10000000-0000-0000-0000-000000000004','lexicon','1','1',repeat('a',64),'{}'),
                            ('10000000-0000-0000-0000-000000000005','grammar-toeic','1','1',repeat('b',64),'{}');
                    INSERT INTO lexicon_headword(id,language,identity_form) VALUES('10000000-0000-0000-0000-000000000006','en','learn');
                    INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,display_form,lookup_form,entry_type)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000006',gen_random_uuid(),'learn','learn','WORD');
                    INSERT INTO assessment_item(id,release_id,package_item_id,kind,options,correct_option)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000005',gen_random_uuid(),'MULTIPLE_CHOICE','[]','A');
                    INSERT INTO toeic_test_catalog(id,year,test_number,publication_status,access_mode,reconciliation_status)
                      VALUES('10000000-0000-0000-0000-000000000007',2026,1,'PUBLISHED','PUBLIC','CONFIRMED');
                    INSERT INTO toeic_test_version(id,release_id,catalog_id,package_test_id,name,year,test_number,set_id,total_questions,source_is_free,source_is_hidden)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000007',gen_random_uuid(),'Imported TOEIC',2026,1,gen_random_uuid(),200,true,false);
                    INSERT INTO grammar_topic_catalog(id,publication_status,access_mode,reconciliation_status)
                      VALUES('10000000-0000-0000-0000-000000000008','PUBLISHED','PUBLIC','CONFIRMED');
                    INSERT INTO grammar_topic_version(id,release_id,catalog_id,package_topic_id,title_en,title_vi,order_index,source_access_level,source_is_hidden)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000008',gen_random_uuid(),'Tenses','Các thì',1,'PUBLIC',false);
                    INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000004','APPLY','SUCCEEDED',now());
                    INSERT INTO dataset_active_release(domain,release_id,activated_by)
                      VALUES('lexicon','10000000-0000-0000-0000-000000000004','reset-test');
                    INSERT INTO dataset_activation_history(id,domain,release_id,action,activated_by)
                      VALUES(gen_random_uuid(),'lexicon','10000000-0000-0000-0000-000000000004','ACTIVATE','reset-test');
                    INSERT INTO vocabulary_card(id,learner_id,headword_id,next_review_at)
                      VALUES(gen_random_uuid(),'10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000006',now());
                    INSERT INTO vocabulary_review(id,card_id,rating,next_due_at) SELECT gen_random_uuid(),id,'GOOD',now() FROM vocabulary_card;
                    """);
                String reset=Files.readString(Path.of("../../infra/postgres/reset-lessons.sql"));
                assertThatThrownBy(()->sql.execute(reset)).hasMessageContaining("active lesson jobs");sql.execute("ROLLBACK");
                assertThat(count(sql,"lesson")).isEqualTo(1);
                assertThat(count(sql,"lesson_job_diagnostic")).isEqualTo(1);
                sql.execute("UPDATE background_job SET status='CANCELLED' WHERE job_type='LESSON_AUTHORING'");
                sql.execute("UPDATE vocabulary_card SET source_context_id='10000000-0000-0000-0000-000000000001',source_context_type='LESSON'");
                assertThatThrownBy(()->sql.execute(reset)).hasMessageContaining("Detach curriculum or vocabulary");sql.execute("ROLLBACK");
                sql.execute("UPDATE vocabulary_card SET source_context_id=NULL");
                List<String> kept=List.of("app_user","ai_provider","ai_capability_route","module_runtime_config","dataset_release","dataset_import_run","dataset_active_release","dataset_activation_history","lexicon_headword","lexicon_entry","assessment_item","toeic_test_catalog","toeic_test_version","grammar_topic_catalog","grammar_topic_version","vocabulary_card","vocabulary_review","lesson_category","lesson_youtube_channel","lesson_audio_source");
                Map<String,Long> before=new HashMap<>();for(String table:kept) before.put(table,count(sql,table));
                sql.execute(reset);
                for(String table:kept) assertThat(count(sql,table)).as(table).isEqualTo(before.get(table));
                assertThat(count(sql,"lesson")).isZero();assertThat(count(sql,"lesson_metadata")).isZero();assertThat(count(sql,"lesson_authoring_job")).isZero();
                assertThat(count(sql,"lesson_job_diagnostic")).isZero();
                assertThat(count(sql,"lesson_sentence")).isZero();assertThat(count(sql,"lesson_activity")).isZero();assertThat(count(sql,"lesson_progress")).isZero();
                assertThat(count(sql,"speech_attempt")).isEqualTo(1);assertThat(count(sql,"background_job")).isEqualTo(1);
                try(var result=sql.executeQuery("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'lesson%published_guard' AND tgenabled='D'")) {result.next();assertThat(result.getLong(1)).isZero();}
            }
        }
    }
    private static long count(Statement sql,String table) throws SQLException {
        try(var result=sql.executeQuery("SELECT count(*) FROM "+table)) {result.next();return result.getLong(1);}
    }
}
