package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.platform.content.AiDraftRequests;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.assessment.application.QuestionAi;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.assessment.infrastructure.persistence.JdbcQuestionSource;
import com.lyreo.ai.application.execution.AiInvocationService;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.contracts.errors.StateConflictException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=LibraryPublicationIT.Config.class)
class LibraryPublicationIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({DocumentStore.class,AiDraftRequests.class,AssessmentLibrary.class,QuestionAi.class,JdbcQuestionSource.class})
    static class Config {
        @org.springframework.context.annotation.Bean BackgroundJobService jobs() {return mock(BackgroundJobService.class);}
        @org.springframework.context.annotation.Bean AssetStore assets() {return mock(AssetStore.class);}
        @org.springframework.context.annotation.Bean AiInvocationService invocation() {return mock(AiInvocationService.class);}
    }
    @Autowired DocumentStore documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired QuestionAi ai;
    @Autowired AssessmentLibrary questions;
    @Autowired ObjectMapper json;

    public record Content(String title,String answer) {}

    @Test void publicationNeedsNoReasonAndTracksContentInsteadOfVisibilityRevisions() {
        UUID id=UUID.randomUUID();var content=new Content("Question","A");
        var draft=documents.save("test",id,0,content,"admin",Content.class);
        assertThat(draft.hasUnpublishedChanges()).isTrue();
        var published=documents.publish("test",id,draft.revision(),null,"admin",Content.class);
        assertThat(published.hasUnpublishedChanges()).isFalse();
        var repeated=documents.publish("test",id,published.revision(),"","admin",Content.class);
        assertThat(repeated.revision()).isEqualTo(published.revision());
        assertThat(documents.history("test",id,Content.class)).hasSize(1);
        var hidden=documents.status("test",id,published.revision(),"HIDDEN","admin",Content.class);
        assertThat(hidden.hasUnpublishedChanges()).isFalse();
        var edited=documents.save("test",id,hidden.revision(),new Content("Question","B"),"admin",Content.class);
        assertThat(edited.hasUnpublishedChanges()).isTrue();
        assertThat(documents.find("test",id,Content.class).orElseThrow().hasUnpublishedChanges()).isTrue();
        var applied=documents.publish("test",id,edited.revision(),null,"admin",Content.class);
        assertThat(applied.status()).isEqualTo("HIDDEN");
        assertThat(applied.hasUnpublishedChanges()).isFalse();
        assertThat(documents.history("test",id,Content.class)).hasSize(2);
        var unchanged=documents.save("test",id,applied.revision(),applied.draft(),"admin",Content.class);
        assertThat(unchanged.hasUnpublishedChanges()).isFalse();
    }

    @Test void sourceVisibilityOverlayDoesNotPretendTheSourceNeedsPublishing() {
        UUID id=UUID.randomUUID();var source=new Content("Imported","A");
        var saved=documents.save("test",id,0,source,"admin",Content.class);
        var same=documents.againstSource(saved,source,"PUBLISHED");
        assertThat(same.status()).isEqualTo("PUBLISHED");
        assertThat(same.hasUnpublishedChanges()).isFalse();
        var edited=documents.save("test",id,saved.revision(),new Content("Imported","B"),"admin",Content.class);
        assertThat(documents.againstSource(edited,source,"PUBLISHED").hasUnpublishedChanges()).isTrue();
    }

    @Test void editingAfterTakingSourceDoesNotReactivateAnOldPublication() {
        UUID id=UUID.randomUUID(),release=UUID.randomUUID(),alias=UUID.randomUUID();
        var draft=documents.save("test",id,0,new Content("Old override","A"),"admin",Content.class);
        var published=documents.publish("test",id,draft.revision(),"Reviewed","admin",Content.class);
        jdbc.update("INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json) VALUES(?,'lexicon',?,'1.0.0',?,CAST('{}' AS jsonb))",release,release.toString().replace("-",""),"a".repeat(64));
        jdbc.update("INSERT INTO content_source_binding(namespace,document_id,domain,base_release_id,identity,enabled) VALUES('test',?,'lexicon',?,CAST('{}' AS jsonb),false)",id,release);
        jdbc.update("INSERT INTO content_source_alias(namespace,source_id,document_id) VALUES('test',?,?)",alias,id);
        assertThat(documents.sourceSelected("test",alias)).isTrue();
        var saved=documents.save("test",alias,published.revision(),new Content("New draft","B"),"admin",Content.class);
        assertThat(saved.id()).isEqualTo(id);
        assertThat(saved.publicationId()).isNull();
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(documents.published("test",alias,Content.class)).isEmpty();
        assertThat(documents.history("test",alias,Content.class)).hasSize(1);
        documents.publish("test",alias,saved.revision(),"Reviewed new source","admin",Content.class);
        assertThat(documents.published("test",alias,Content.class).orElseThrow().content().answer()).isEqualTo("B");
    }

    @Test void onlyUnpublishedUnreferencedDraftsCanBeDeleted() {
        UUID id=UUID.randomUUID();
        var draft=documents.save("test",id,0,new Content("Delete","A"),"admin",Content.class);
        assertThatThrownBy(()->documents.deleteDraft("test",id,0,"admin")).isInstanceOf(StateConflictException.class);
        documents.deleteDraft("test",id,draft.revision(),"admin");
        assertThat(documents.find("test",id,Content.class)).isEmpty();
        assertThat(documents.audit("test",id)).extracting(row->row.get("action")).contains("DELETE");
        UUID publishedId=UUID.randomUUID();var saved=documents.save("test",publishedId,0,new Content("Keep","A"),"admin",Content.class);
        var published=documents.publish("test",publishedId,saved.revision(),"Reviewed","admin",Content.class);
        assertThatThrownBy(()->documents.deleteDraft("test",publishedId,published.revision(),"admin")).isInstanceOf(StateConflictException.class);
    }

    @Test void draftPublicationRestoreAndConcurrentEditsKeepHistory() {
        UUID id=UUID.randomUUID();var first=new Content("Question","A");
        var draft=documents.save("test",id,0,first,"admin",Content.class);
        assertThat(documents.published("test",id,Content.class)).isEmpty();
        assertThatThrownBy(()->documents.save("test",id,0,new Content("Lost update","B"),"other",Content.class)).isInstanceOf(StateConflictException.class);
        var published=documents.publish("test",id,draft.revision(),"Reviewed","admin",Content.class);
        var old=documents.published("test",id,Content.class).orElseThrow();
        var edited=documents.save("test",id,published.revision(),new Content("Question","B"),"admin",Content.class);
        assertThat(documents.published("test",id,Content.class).orElseThrow().content().answer()).isEqualTo("A");
        assertThatThrownBy(()->documents.publish("test",id,published.revision(),"Stale","other",Content.class)).isInstanceOf(StateConflictException.class);
        var next=documents.publish("test",id,edited.revision(),"Corrected answer","admin",Content.class);
        assertThat(documents.published("test",id,Content.class).orElseThrow().content().answer()).isEqualTo("B");
        var restored=documents.restore("test",id,next.revision(),old.id(),"admin",Content.class);
        assertThat(restored.publicationId()).isNotEqualTo(old.id());
        assertThat(documents.published("test",id,Content.class).orElseThrow().content()).isEqualTo(first);
        assertThat(documents.history("test",id,Content.class)).hasSize(3);
        assertThatThrownBy(()->jdbc.update("UPDATE content_publication SET reason='tampered' WHERE id=?",old.id())).hasMessageContaining("Immutable");
        var hidden=documents.status("test",id,restored.revision(),"HIDDEN","admin",Content.class);
        assertThat(documents.published("test",id,Content.class)).isEmpty();
        documents.status("test",id,hidden.revision(),"PUBLISHED","admin",Content.class);
        assertThat(documents.published("test",id,Content.class)).isPresent();
    }
    @Test void aiAcceptanceCommitsDraftAndLedgerTogetherWithoutChangingPublication() {
        var question=new QuestionContent("Choose","MCQ_SINGLE",List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two")),"A",1,null,null,null,null,null,List.of());
        var draft=questions.create(question,"admin");
        var published=questions.publish(draft.id(),draft.revision(),"Reviewed source","admin");
        UUID request=UUID.randomUUID(),job=UUID.randomUUID();
        jdbc.update("INSERT INTO background_job(id,job_type,owner_module,status) VALUES(?,'QUESTION_EDITORIAL_AI','assessment','SUCCEEDED')",job);
        var output=Map.of("suggestions",List.of(Map.of("field","translationVi","value","Chọn"),Map.of("field","explanationVi","value","Giải thích")),"candidates",List.of(),"warnings",List.of());
        jdbc.update("INSERT INTO content_ai_request(id,namespace,document_id,base_revision,operation,input,output,job_id,created_by) VALUES(?,'assessment',?,?,'SUGGEST',CAST('{\"count\":20}' AS jsonb),CAST(? AS jsonb),?,'admin')",request,draft.id(),published.revision(),json.writeValueAsString(output),job);
        var accepted=ai.accept(draft.id(),request,published.revision(),"suggestion",0,"admin").document();
        assertThat(accepted.draft().translationVi()).isEqualTo("Chọn");
        assertThat(accepted.draft().correctOption()).isEqualTo("A");
        assertThat(documents.publication("assessment",draft.id(),published.publicationId(),QuestionContent.class).content().translationVi()).isNull();
        assertThat(ai.accept(draft.id(),request,published.revision(),"suggestion",0,"admin").document().revision()).isEqualTo(accepted.revision());
        var manual=questions.save(draft.id(),accepted.revision(),question,"other");
        assertThatThrownBy(()->ai.accept(draft.id(),request,manual.revision(),"suggestion",1,"admin")).isInstanceOf(StateConflictException.class);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(applied,'suggestion:1') FROM content_ai_request WHERE id=?",Boolean.class,request)).isFalse();
        assertThat(questions.get(draft.id()).revision()).isEqualTo(manual.revision());
    }

}
