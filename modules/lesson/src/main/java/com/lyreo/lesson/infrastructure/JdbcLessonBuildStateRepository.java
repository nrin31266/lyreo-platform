package com.lyreo.lesson.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.lesson.application.LessonBuildStateRepository;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonBuildStep;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcLessonBuildStateRepository implements LessonBuildStateRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLessonBuildStateRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void create(UUID backgroundJobId, UUID lessonId, LessonBuildPlan plan, String providerSnapshotJson) {
        try {
            String planJson = mapper.writeValueAsString(plan);
            jdbc.update("""
                INSERT INTO lesson_build_job(job_id, lesson_id, build_plan_json, provider_snapshot_json, created_at)
                VALUES (:jobId, :lessonId, CAST(:plan AS jsonb), CAST(:providers AS jsonb), now())
                """, new MapSqlParameterSource()
                    .addValue("jobId", backgroundJobId)
                    .addValue("lessonId", lessonId)
                    .addValue("plan", planJson)
                    .addValue("providers", providerSnapshotJson == null ? "{}" : providerSnapshotJson));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist lesson build plan", e);
        }
    }

    @Override
    public UUID lessonIdForJob(UUID backgroundJobId) {
        return jdbc.queryForObject("""
            SELECT lesson_id FROM lesson_build_job WHERE job_id = :jobId
            """, Map.of("jobId", backgroundJobId), UUID.class);
    }

    @Override
    public LessonBuildPlan planForJob(UUID backgroundJobId) {
        String json = jdbc.queryForObject("""
            SELECT build_plan_json::text FROM lesson_build_job WHERE job_id = :jobId
            """, Map.of("jobId", backgroundJobId), String.class);
        try {
            return mapper.readValue(json, LessonBuildPlan.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read lesson build plan", e);
        }
    }

    @Override
    public boolean stepDone(UUID jobId, LessonBuildStep step) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM lesson_build_job_step
             WHERE job_id = :jobId AND step = :step AND status = 'DONE'
            """, Map.of("jobId", jobId, "step", step.name()), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void stepStarted(UUID jobId, LessonBuildStep step) {
        jdbc.update("""
            INSERT INTO lesson_build_job_step(id, job_id, step, status, started_at)
            VALUES (:id, :jobId, :step, 'RUNNING', now())
            ON CONFLICT (job_id, step)
            DO UPDATE SET status = 'RUNNING', started_at = now(), error_message = NULL
            """, Map.of("id", UUID.randomUUID(), "jobId", jobId, "step", step.name()));
    }

    @Override
    public void stepDone(UUID jobId, LessonBuildStep step, UUID aiInvocationId, String artifactKey) {
        jdbc.update("""
            UPDATE lesson_build_job_step
               SET status = 'DONE',
                   finished_at = now(),
                   ai_invocation_id = :aiInvocationId,
                   output_artifact_key = :artifactKey,
                   error_message = NULL
             WHERE job_id = :jobId AND step = :step
            """, new MapSqlParameterSource()
                .addValue("jobId", jobId)
                .addValue("step", step.name())
                .addValue("aiInvocationId", aiInvocationId)
                .addValue("artifactKey", artifactKey));
    }

    @Override
    public void stepFailed(UUID jobId, LessonBuildStep step, String error) {
        jdbc.update("""
            UPDATE lesson_build_job_step
               SET status = 'FAILED', finished_at = now(), error_message = :error
             WHERE job_id = :jobId AND step = :step
            """, Map.of("jobId", jobId, "step", step.name(), "error", error == null ? "" : error));
    }
}
