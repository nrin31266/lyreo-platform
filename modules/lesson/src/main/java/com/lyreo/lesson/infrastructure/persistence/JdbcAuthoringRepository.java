package com.lyreo.lesson.infrastructure.persistence;

import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

public final class JdbcAuthoringRepository implements AuthoringRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BackgroundJobService jobs;
    public JdbcAuthoringRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, BackgroundJobService jobs) {
        this.jdbc = jdbc;
        this.mapper = mapper;this.jobs=jobs;
    }
    @Override public Optional<AuthoringLesson> find(UUID id, boolean lock) {
        var found = jdbc.query("""
            SELECT a.*, m.title,m.title_automatic,l.source_type,l.source_reference,l.canonical_audio_object_key,l.status
              FROM lesson_authoring a JOIN lesson l ON l.id=a.lesson_id JOIN lesson_metadata m ON m.lesson_id=l.id WHERE a.lesson_id=:id AND m.deleted_at IS NULL
            """ + (lock ? " FOR UPDATE OF a,l" : ""), Map.of("id",id), this::read);
        if (found.isEmpty()) return Optional.empty();
        var lesson = found.getFirst();
        lesson.jobs = jdbc.query("SELECT * FROM lesson_authoring_job WHERE lesson_id=:id",
            Map.of("id",id), (rs,n) -> {
                UUID jobId=rs.getObject("job_id",UUID.class);
                var job=jobs.findById(jobId).orElseThrow();
                return new AuthoringLesson.Job(jobId,rs.getString("operation"),job.status().name(),job.currentStep(),
                    job.progressPercent(),job.errorMessage(),job.errorCode(),job.nextRetryAt(),mapper.readValue(rs.getString("usage_json"),
                    mapper.getTypeFactory().constructCollectionType(List.class,AuthoringLesson.Invocation.class)),job.createdAt(),job.startedAt(),job.finishedAt());
            }).stream().sorted(java.util.Comparator.comparing(AuthoringLesson.Job::createdAt).thenComparing(AuthoringLesson.Job::jobId)).toList();
        return Optional.of(lesson);
    }
    private AuthoringLesson read(ResultSet rs,int row) throws SQLException {
        var lesson = new AuthoringLesson();
        lesson.id=rs.getObject("lesson_id",UUID.class);
        lesson.title=rs.getString("title");lesson.automaticTitle=rs.getBoolean("title_automatic"); lesson.sourceReference=rs.getString("source_reference");
        lesson.videoId=rs.getString("video_id");lesson.sourceType=rs.getString("source_type");
        lesson.audioSourceId=rs.getObject("audio_source_id",UUID.class);lesson.ttsVoice=rs.getString("tts_voice");
        lesson.startMs=rs.getLong("source_start_ms"); lesson.endMs=rs.getLong("source_end_ms");
        lesson.revision=rs.getLong("revision"); lesson.transcriptRevision=rs.getLong("transcript_revision");
        lesson.approvedRevision=rs.getObject("approved_revision",Long.class);
        lesson.karaokeReviewedRevision=rs.getObject("karaoke_reviewed_revision",Long.class);
        lesson.semanticRevision=rs.getObject("semantic_revision",Long.class);
        lesson.options=mapper.readValue(rs.getString("generation_options"),com.lyreo.lesson.domain.authoring.AuthoringOptions.class);
        lesson.timingEvidence=mapper.readValue(rs.getString("timing_evidence"),java.util.Map.class);
        lesson.timingStatus=rs.getString("timing_status"); lesson.status=rs.getString("status");
        lesson.activeJobId=rs.getObject("active_job_id",UUID.class);
        lesson.audioObjectKey=rs.getString("canonical_audio_object_key");
        lesson.publishedAt=rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant();
        lesson.sentences=mapper.readValue(rs.getString("document"),mapper.getTypeFactory().constructCollectionType(List.class,AuthoringSentence.class));
        return lesson;
    }
    @Override public List<UsedRange> usedRanges(String videoId) {
        return jdbc.query("""
            SELECT l.id,m.title,a.source_start_ms,a.source_end_ms,l.status FROM lesson_authoring a
              JOIN lesson l ON l.id=a.lesson_id JOIN lesson_metadata m ON m.lesson_id=l.id WHERE a.video_id=:video AND m.deleted_at IS NULL ORDER BY a.source_start_ms
            """,Map.of("video",videoId),(rs,n)->new UsedRange(rs.getObject("id",UUID.class),rs.getString("title"),
                rs.getLong("source_start_ms"),rs.getLong("source_end_ms"),rs.getString("status")));
    }
    @Override public void create(AuthoringLesson lesson) {
        var params=params(lesson);
        jdbc.update("""
            INSERT INTO lesson(id,title,source_type,source_reference,status)
            VALUES(:id,:title,:sourceType,:reference,'TRANSCRIPT_REVIEW')
            """,params);
        jdbc.update("UPDATE lesson_metadata SET title_automatic=:automatic WHERE lesson_id=:id",params.addValue("automatic",lesson.automaticTitle));
        jdbc.update("""
            INSERT INTO lesson_authoring(lesson_id,video_id,source_start_ms,source_end_ms,audio_source_id,tts_voice,document)
            VALUES(:id,:video,:start,:end,:audioSource,:voice,CAST(:document AS jsonb))
            """,params);
    }
    @Override public void renameAutomatic(UUID id,String title) {
        jdbc.update("UPDATE lesson_metadata SET title=:title,title_automatic=FALSE,updated_at=now() WHERE lesson_id=:id AND title_automatic",
            Map.of("id",id,"title",title));
    }
    @Override public void save(AuthoringLesson lesson) {
        var params=params(lesson);
        jdbc.update("""
            UPDATE lesson SET status=:status,canonical_audio_object_key=:audio,updated_at=now() WHERE id=:id
            """,params);
        int rows=jdbc.update("""
            UPDATE lesson_authoring SET revision=revision+1,transcript_revision=:transcriptRevision,
                source_end_ms=:end,tts_voice=:voice,
                approved_revision=:approved,karaoke_reviewed_revision=:karaoke,semantic_revision=:semantic,
                generation_options=CAST(:options AS jsonb),timing_evidence=CAST(:evidence AS jsonb),timing_status=:timing,active_job_id=:active,document=CAST(:document AS jsonb),published_at=:published
             WHERE lesson_id=:id AND revision=:revision AND published_at IS NULL
            """,params);
        if(rows!=1) throw new IllegalStateException("Draft revision changed while committing");
        lesson.revision++;
    }
    private MapSqlParameterSource params(AuthoringLesson l) {
        return new MapSqlParameterSource().addValue("id",l.id).addValue("title",l.title)
            .addValue("reference",l.sourceReference).addValue("video",l.videoId)
            .addValue("sourceType",l.sourceType).addValue("audioSource",l.audioSourceId).addValue("voice",l.ttsVoice)
            .addValue("start",l.startMs).addValue("end",l.endMs>0 ? l.endMs : null).addValue("revision",l.revision)
            .addValue("transcriptRevision",l.transcriptRevision).addValue("approved",l.approvedRevision)
            .addValue("karaoke",l.karaokeReviewedRevision).addValue("semantic",l.semanticRevision)
            .addValue("options",mapper.writeValueAsString(l.options)).addValue("evidence",mapper.writeValueAsString(l.timingEvidence)).addValue("timing",l.timingStatus).addValue("status",l.status).addValue("audio",l.audioObjectKey)
            .addValue("active",l.activeJobId).addValue("document",mapper.writeValueAsString(l.sentences))
            .addValue("published",l.publishedAt == null ? null : java.sql.Timestamp.from(l.publishedAt));
    }
    @Override public void addJob(UUID lesson,UUID job,String operation,long revision) {
        jdbc.update("""
            INSERT INTO lesson_authoring_job(job_id,lesson_id,operation,transcript_revision)
            VALUES(:job,:lesson,:operation,:revision)
            """,Map.of("job",job,"lesson",lesson,"operation",operation,"revision",revision));
    }
    @Override public JobInput jobInput(UUID id) {
        return jdbc.queryForObject("SELECT lesson_id,operation,transcript_revision,completed FROM lesson_authoring_job WHERE job_id=:id",
            Map.of("id",id),(rs,n)->new JobInput(rs.getObject("lesson_id",UUID.class),rs.getString("operation"),rs.getLong("transcript_revision"),rs.getBoolean("completed")));
    }
    @Override public void assertJobLease(UUID jobId,String owner) {
        jobs.fenceForCommit(jobId,owner);
    }
    @Override public void completeJob(UUID jobId) {
        jdbc.update("UPDATE lesson_authoring_job SET completed=TRUE WHERE job_id=:id",Map.of("id",jobId));
    }
    @Override public void usage(UUID job,AuthoringLesson.Invocation invocation) {
        jdbc.update("""
            UPDATE lesson_authoring_job SET usage_json=usage_json || CAST(:usage AS jsonb) WHERE job_id=:job
            """,Map.of("job",job,"usage",mapper.writeValueAsString(List.of(invocation))));
    }
}
