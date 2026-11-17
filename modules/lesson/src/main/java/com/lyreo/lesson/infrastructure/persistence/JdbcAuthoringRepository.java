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
                    mapper.getTypeFactory().constructCollectionType(List.class,AuthoringLesson.Invocation.class)),job.createdAt(),job.startedAt(),job.finishedAt(),
                    job.attemptCount(),rs.getString("result_json")==null?null:mapper.readValue(rs.getString("result_json"),com.lyreo.lesson.domain.authoring.AuthoringJobResult.class));
            }).stream().sorted(java.util.Comparator.comparing(AuthoringLesson.Job::createdAt).thenComparing(AuthoringLesson.Job::jobId)).toList();
        return Optional.of(lesson);
    }
    private AuthoringLesson read(ResultSet rs,int row) throws SQLException {
        var lesson = new AuthoringLesson();
        lesson.autoContinue=rs.getBoolean("auto_continue");lesson.publishedBy=rs.getObject("published_by",UUID.class);
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
        lesson.profile=mapper.readValue(rs.getString("content_profile"),com.lyreo.lesson.domain.authoring.AuthoringProfile.class);
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
        // Recreate cached channel metadata and serialize lesson creation with channel deletion.
        if (lesson.videoId != null) jdbc.update("""
            INSERT INTO lesson_youtube_channel(channel_id,title)
            SELECT channel_id,COALESCE(NULLIF(channel_title,''),channel_id)
            FROM lesson_youtube_source WHERE video_id=:video AND channel_id IS NOT NULL AND channel_id<>''
            ON CONFLICT(channel_id) DO UPDATE SET channel_id=excluded.channel_id
            """,params);
        jdbc.update("""
            INSERT INTO lesson(id,title,source_type,source_reference,status)
            VALUES(:id,:title,:sourceType,:reference,'TRANSCRIPT_REVIEW')
            """,params);
        jdbc.update("UPDATE lesson_metadata SET title_automatic=:automatic WHERE lesson_id=:id",params.addValue("automatic",lesson.automaticTitle));
        jdbc.update("""
            INSERT INTO lesson_authoring(lesson_id,video_id,source_start_ms,source_end_ms,audio_source_id,tts_voice,document,generation_options)
            VALUES(:id,:video,:start,:end,:audioSource,:voice,CAST(:document AS jsonb),CAST(:options AS jsonb))
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
                auto_continue=:autoContinue,published_by=:publishedBy,generation_options=CAST(:options AS jsonb),content_profile=CAST(:profile AS jsonb),timing_evidence=CAST(:evidence AS jsonb),timing_status=:timing,active_job_id=:active,document=CAST(:document AS jsonb),published_at=:published
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
            .addValue("autoContinue",l.autoContinue).addValue("publishedBy",l.publishedBy).addValue("options",mapper.writeValueAsString(l.options)).addValue("profile",mapper.writeValueAsString(l.profile)).addValue("evidence",mapper.writeValueAsString(l.timingEvidence)).addValue("timing",l.timingStatus).addValue("status",l.status).addValue("audio",l.audioObjectKey)
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
    @Override public void jobResult(UUID job,com.lyreo.lesson.domain.authoring.AuthoringJobResult result) {
        jdbc.update("UPDATE lesson_authoring_job SET result_json=CAST(:result AS jsonb) WHERE job_id=:job",
            Map.of("job",job,"result",mapper.writeValueAsString(result)));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void purgeDiagnostics() {jdbc.update("DELETE FROM lesson_job_diagnostic WHERE expires_at<now()",Map.of());}
    @Override public void diagnostic(UUID job,UUID invocation,Map<String,Object> payload) {
        String json=mapper.writeValueAsString(com.lyreo.lesson.domain.authoring.AuthoringDiagnostic.clean(payload));
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144) json=mapper.writeValueAsString(Map.of("truncated",true,"preview",json.substring(0,Math.min(40000,json.length()))));
        purgeDiagnostics();
        jdbc.update("INSERT INTO lesson_job_diagnostic(id,job_id,invocation_id,payload) SELECT :id,:job,:invocation,CAST(:payload AS jsonb) WHERE (SELECT COALESCE(sum(pg_column_size(payload)),0) FROM lesson_job_diagnostic WHERE job_id=:job)<8388608",
            new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("job",job).addValue("invocation",invocation).addValue("payload",json));
    }
    @Override public List<Map<String,Object>> diagnostics(UUID lesson,UUID job) {
        return jdbc.query("SELECT d.* FROM lesson_job_diagnostic d JOIN lesson_authoring_job j ON j.job_id=d.job_id WHERE j.lesson_id=:lesson AND d.job_id=:job AND d.expires_at>now() ORDER BY d.created_at",
            Map.of("lesson",lesson,"job",job),(rs,n)->Map.of("id",rs.getObject("id").toString(),"createdAt",rs.getTimestamp("created_at").toInstant().toString(),"invocationId",rs.getObject("invocation_id")==null?"":rs.getObject("invocation_id").toString(),"payload",mapper.readValue(rs.getString("payload"),Map.class)));
    }
    @Override public void copyMetadata(UUID original,UUID copy) {
        jdbc.update("UPDATE lesson_metadata c SET description=o.description,image_url=o.image_url FROM lesson_metadata o WHERE c.lesson_id=:copy AND o.lesson_id=:original",Map.of("copy",copy,"original",original));
        jdbc.update("INSERT INTO lesson_category_membership(lesson_id,category_id) SELECT :copy,category_id FROM lesson_category_membership WHERE lesson_id=:original",Map.of("copy",copy,"original",original));
    }
    @Override public void publish(AuthoringLesson lesson) {
        for(int i=0;i<lesson.sentences.size();i++) {
            var s=lesson.sentences.get(i);var args=new MapSqlParameterSource().addValue("id",s.id).addValue("lesson",lesson.id).addValue("position",i).addValue("text",s.text).addValue("start",s.words.getFirst().startMs()).addValue("end",s.words.getLast().endMs());
            jdbc.update("INSERT INTO lesson_sentence(id,lesson_id,position,text,audio_start_ms,audio_end_ms) VALUES(:id,:lesson,:position,:text,:start,:end)",args);
            for(int w=0;w<s.words.size();w++) {var word=s.words.get(w);jdbc.update("INSERT INTO lesson_word_timestamp(id,sentence_id,position,surface_text,start_ms,end_ms) VALUES(:wid,:id,:p,:text,:start,:end)",args.addValue("wid",UUID.randomUUID()).addValue("p",w).addValue("text",word.text()).addValue("start",word.startMs()).addValue("end",word.endMs()));}
            if(s.ipa!=null && !s.ipa.isBlank()) jdbc.update("INSERT INTO sentence_pronunciation(id,sentence_id,accent,ipa,provider,model,text_hash) VALUES(:pid,:id,:accent,:ipa,'LOCAL',:model,:hash)",args.addValue("pid",UUID.randomUUID()).addValue("accent",s.ipaAccent==null?lesson.options.accent():s.ipaAccent).addValue("ipa",s.ipa).addValue("model",s.ipaVersion).addValue("hash",com.lyreo.lesson.domain.authoring.AuthoringContent.hash(s.text)));
            jdbc.update("INSERT INTO lesson_annotation(id,lesson_id,sentence_id,annotation_type,payload_json) VALUES(:aid,:lesson,:id,'CONTENT',CAST(:payload AS jsonb))",args.addValue("aid",UUID.randomUUID()).addValue("payload",mapper.writeValueAsString(s)));
        }
        var publishedActivities=new java.util.TreeSet<>(lesson.options.activities());publishedActivities.add("WATCH");
        for(String activity:publishedActivities) {
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO lesson_activity(id,lesson_id,activity_type,position,config_json) VALUES(:id,:lesson,:type,:position,CAST(:config AS jsonb))",Map.of("id",id,"lesson",lesson.id,"type",activity,"position",activity.equals("WATCH")?0:activity.equals("DICTATION")?1:2,"config",mapper.writeValueAsString(Map.of("options",lesson.options,"profile",lesson.profile,"clozeVersion","cloze-v2"))));
            for(var s:lesson.sentences) if(activity.equals("WATCH") || (activity.equals("DICTATION")?s.dictationSelected:s.shadowingSelected)) jdbc.update("INSERT INTO lesson_activity_sentence(activity_id,sentence_id) VALUES(:activity,:sentence)",Map.of("activity",id,"sentence",s.id));
        }
        save(lesson);
    }
}
