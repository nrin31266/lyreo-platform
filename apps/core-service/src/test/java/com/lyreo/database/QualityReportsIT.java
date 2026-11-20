package com.lyreo.database;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.platform.content.*;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import com.lyreo.platform.jobs.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=QualityReportsIT.Config.class)
class QualityReportsIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @Import({QualityReports.class,DocumentStore.class})
    static class Config {@Bean BackgroundJobService jobs() {return mock(BackgroundJobService.class);}}
    @Autowired QualityReports reports;@Autowired DocumentStore documents;@Autowired BackgroundJobService jobs;@Autowired JdbcTemplate jdbc;
    @Test void catalogViewRejectsPreviousRulesUntilTheReportIsCheckedWithCurrentRules() {
        UUID target=UUID.randomUUID(),job=UUID.randomUUID(),report=UUID.randomUUID();String fingerprint="a".repeat(64);
        jdbc.update("INSERT INTO background_job(id,job_type,owner_module,status) VALUES(?,'QUALITY','test','SUCCEEDED')",job);
        jdbc.update("""
            INSERT INTO content_quality_report(id,namespace,target_id,revision,fingerprint,rules_version,job_id,status,checked_at,context)
            VALUES(?,'toeic',?,1,?,'2.1.0',?,'PASSED',now(),'{}'::jsonb)
            """,report,target,fingerprint,job);
        assertThat(jdbc.queryForObject("SELECT status FROM content_quality_current WHERE namespace='toeic' AND target_id=?",String.class,target)).isEqualTo("STALE");
        assertThat(reports.latest("toeic",target,fingerprint).orElseThrow().status()).isEqualTo("STALE");
        jdbc.update("UPDATE content_quality_report SET rules_version=? WHERE id=?",QualityReports.RULES_VERSION,report);
        assertThat(jdbc.queryForObject("SELECT status FROM content_quality_current WHERE namespace='toeic' AND target_id=?",String.class,target)).isEqualTo("PASSED");
        assertThat(reports.latest("toeic",target,fingerprint).orElseThrow().status()).isEqualTo("PASSED");
    }
    @Test void reportsPinDependenciesDeduplicateActiveRunsAndNeverCallFailureAPass() {
        when(jobs.enqueue(anyString(),anyString(),any(),anyInt(),anyInt(),anyString())).thenAnswer(call->{
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO background_job(id,job_type,owner_module,status) VALUES(?,'QUALITY','test','QUEUED')",id);return id;
        });
        UUID target=UUID.randomUUID();var content=Map.of("question","A");String fingerprint=reports.fingerprint(content);
        assertThat(reports.latest("test",target,fingerprint)).isEmpty();
        var running=reports.start("test",target,1,fingerprint,"QUALITY",Map.of());
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(reports.start("test",target,1,fingerprint,"QUALITY",Map.of()).id()).isEqualTo(running.id());
        reports.complete(running.id(),running.jobId(),"worker",List.of(new QualityReports.Issue("ANSWER_INVALID","ERROR","Question 1","Answer is absent","/grammar/questions/1")));
        var blocked=reports.latest("test",target,fingerprint).orElseThrow();
        assertThat(blocked.status()).isEqualTo("BLOCKED");assertThat(blocked.checkedAt()).isNotNull();assertThat(blocked.issues().getFirst().href()).isEqualTo("/grammar/questions/1");
        jdbc.update("UPDATE content_quality_report SET rules_version='old-rules' WHERE id=?",running.id());
        assertThat(reports.latest("test",target,fingerprint).orElseThrow().status()).isEqualTo("STALE");
        jdbc.update("UPDATE content_quality_report SET rules_version=? WHERE id=?",QualityReports.RULES_VERSION,running.id());
        documents.save("assessment",UUID.randomUUID(),0,Map.of("answer","B"),"admin",Map.class);
        assertThat(reports.latest("test",target,reports.fingerprint(content)).orElseThrow().status()).isEqualTo("STALE");
        var next=reports.start("test",target,2,reports.fingerprint(content),"QUALITY",Map.of());
        var failed=mock(BackgroundJob.class);when(failed.status()).thenReturn(BackgroundJobStatus.FAILED);when(jobs.findById(next.jobId())).thenReturn(Optional.of(failed));
        assertThat(reports.get(next.id()).status()).isEqualTo("FAILED");assertThat(reports.get(next.id()).checkedAt()).isNull();
        verify((BackgroundJobService)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs)).fenceForCommit(running.jobId(),"worker");
    }
}
