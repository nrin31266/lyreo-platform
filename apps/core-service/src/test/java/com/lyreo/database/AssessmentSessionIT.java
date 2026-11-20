package com.lyreo.database;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lyreo.assessment.api.AssessmentSessions;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.assessment.infrastructure.persistence.JdbcSessionRepository;
import com.lyreo.contracts.errors.*;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.platform.content.AssetStore;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=AssessmentSessionIT.Config.class)
class AssessmentSessionIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({AssessmentSessions.class,JdbcSessionRepository.class,Events.class})
    static class Config { @Bean AssetStore assets() {return mock(AssetStore.class);} }
    static class Events {
        final List<ToeicAttemptCompletedEvent> completed=new CopyOnWriteArrayList<>();
        @EventListener void onCompletion(ToeicAttemptCompletedEvent event) {completed.add(event);}
    }
    @Autowired AssessmentSessions sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Events events;
    @Test void pinsAnswersProtectsOwnershipAndEmitsCompletionExactlyOnce() {
        UUID user=UUID.randomUUID(),other=UUID.randomUUID(),placement=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",user,user.toString());
        var content=new QuestionContent("Choose","MCQ_SINGLE",List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two")),"A",2,"Private explanation",null,null,null,"Private transcript",List.of());
        var snapshot=new AssessmentSessions.Snapshot("Original publication",UUID.randomUUID(),List.of(new AssessmentSessions.Item(placement,item,null,5,101,null,null,null,content,null)),List.of());
        var started=sessions.start(user,"toeic",UUID.randomUUID(),"DRILL",snapshot);
        assertThat(json.writeValueAsString(started)).doesNotContain("correctOption","Private explanation","Private transcript");
        assertThatThrownBy(()->sessions.get(other,started.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->sessions.save(user,started.id(),started.revision(),Map.of(UUID.randomUUID(),"A"))).isInstanceOf(RequestValidationException.class);
        var answered=sessions.save(user,started.id(),started.revision(),Map.of(placement,"A"));
        assertThatThrownBy(()->sessions.save(user,started.id(),started.revision(),Map.of(placement,"B"))).isInstanceOf(StateConflictException.class);
        var submitted=sessions.submit(user,started.id(),answered.revision());
        assertThat(submitted.result().readingCorrect()).isEqualTo(1);
        assertThat(submitted.result().listeningScaledScore()).isNull();
        assertThat(sessions.submit(user,started.id(),started.revision()).result()).isEqualTo(submitted.result());
        assertThat(events.completed.stream().filter(event->event.attemptId().equals(started.id()))).hasSize(1);
        assertThatThrownBy(()->sessions.save(user,started.id(),submitted.revision(),Map.of(placement,"B"))).isInstanceOf(StateConflictException.class);
        assertThat(sessions.history(user,"toeic",0,20)).extracting(AssessmentSessions.History::id).contains(started.id());
    }

    @Test void simultaneousSubmitsEmitOneFactAndExpiredSessionsCannotContinue() throws Exception {
        UUID user=UUID.randomUUID(),placement=UUID.randomUUID(),item=UUID.randomUUID(),group=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",user,user.toString());
        var question=new QuestionContent("Choose","MCQ_SINGLE",List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two")),"A",1,"Explanation",null,null,null,"Question transcript",List.of());
        var snapshot=new AssessmentSessions.Snapshot("Pinned",null,List.of(new AssessmentSessions.Item(placement,item,null,3,32,null,null,group,question,null)),List.of(new AssessmentSessions.Group(group,3,List.of(),List.of(),"Group transcript","Group translation")));
        var started=sessions.start(user,"toeic",UUID.randomUUID(),"DRILL",snapshot);
        assertThat(json.writeValueAsString(started)).doesNotContain("Group transcript","Question transcript","correctOption");
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->sessions.submit(user,started.id(),started.revision()));
            var second=executor.submit(()->sessions.submit(user,started.id(),started.revision()));
            assertThat(first.get().result()).isEqualTo(second.get().result());
            assertThat(first.get().result().groups().getFirst().transcriptEn()).isEqualTo("Group transcript");
            assertThat(first.get().result().questions().getFirst().content().transcriptEn()).isEqualTo("Question transcript");
            assertThat(first.get().result().correct()).isZero();
        }
        assertThat(events.completed.stream().filter(event->event.attemptId().equals(started.id()))).hasSize(1);
        UUID expired=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO assessment_session(id,learner_id,domain,mode,snapshot,created_at,expires_at)
            VALUES(?,?,'toeic','DRILL',CAST(? AS jsonb),now()-interval '25 hours',now()-interval '1 hour')
            """,expired,user,json.writeValueAsString(snapshot));
        assertThat(sessions.get(user,expired).status()).isEqualTo("EXPIRED");
        assertThatThrownBy(()->sessions.save(user,expired,1,Map.of())).isInstanceOf(StateConflictException.class);
        assertThatThrownBy(()->sessions.submit(user,expired,1)).isInstanceOf(StateConflictException.class);
    }
    @Test void clozeGapSurvivesDrillRenumberingSubmissionAndHistory() {
        UUID user=UUID.randomUUID(),placement=UUID.randomUUID(),group=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",user,user.toString());
        var question=new QuestionContent(null,"CLOZE",List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two")),"A",1,null,null,null,null,null,List.of());
        var snapshot=new AssessmentSessions.Snapshot("Part 6 drill",null,List.of(new AssessmentSessions.Item(placement,UUID.randomUUID(),null,6,1,null,null,group,question,131)),List.of(new AssessmentSessions.Group(group,6,List.of("<p>Gap (131)</p>"),List.of())));
        var started=sessions.start(user,"toeic",UUID.randomUUID(),"DRILL",snapshot);
        assertThat(started.questions().getFirst().gapNumber()).isEqualTo(131);
        assertThat(sessions.get(user,started.id()).questions().getFirst().gapNumber()).isEqualTo(131);
        var submitted=sessions.submit(user,started.id(),started.revision());
        assertThat(submitted.result().questions().getFirst().gapNumber()).isEqualTo(131);
        assertThat(submitted.result().groups().getFirst().documents()).containsExactly("<p>Gap (131)</p>");
    }

    @Test void partTwoHidesSpokenTextUntilFeedback() {
        UUID user = UUID.randomUUID(), placement = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)", user, user.toString());
        var question = new QuestionContent("Where is the station?", "MULTIPLE_CHOICE",
            List.of(new QuestionContent.Option("A", "On Main Street"), new QuestionContent.Option("B", "At noon"),
                new QuestionContent.Option("C", "By train")),
            "A", 1, null, null, null, null, null, List.of());
        var item = new AssessmentSessions.Item(placement, UUID.randomUUID(), null, 2, 7, null, null, null, question, null);
        var snapshot = new AssessmentSessions.Snapshot("Listening", null, List.of(item), List.of());
        var started = sessions.start(user, "toeic", UUID.randomUUID(), "LISTENING", snapshot);

        assertThat(started.questions().getFirst().text()).isNull();
        assertThat(started.questions().getFirst().options()).extracting(QuestionContent.Option::text).containsOnly("");
        var submitted = sessions.submit(user, started.id(), started.revision());
        assertThat(submitted.result().questions().getFirst().content().title()).isEqualTo(question.title());
    }
}
