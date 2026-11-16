package com.lyreo.lesson.infrastructure.persistence;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.application.port.SourcePreviewPort;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import javax.sound.sampled.AudioSystem;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcSourcePreview implements SourcePreviewPort,JobHandler {
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthoringSourcePort source;
    private final BackgroundJobService jobs;
    private final ObjectStoragePort storage;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    public JdbcSourcePreview(NamedParameterJdbcTemplate jdbc,AuthoringSourcePort source,BackgroundJobService jobs,
        ObjectStoragePort storage,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.source=source;this.jobs=jobs;this.storage=storage;this.mapper=mapper;transactions=new TransactionTemplate(manager);
    }
    @Override public String jobType() {return "LESSON_SOURCE_PREVIEW";}
    @Override public Preview create(String reference,long startMs,long endMs) {
        var info=source.inspect(reference);
        if(startMs<0 || endMs<=startMs || endMs>info.durationMs() || endMs-startMs>600_000)
            throw new RequestValidationException("Select a range within the video, at most 10 minutes");
        var args=new HashMap<String,Object>();args.put("video",info.videoId());args.put("start",startMs);args.put("end",endMs);
        var cached=jdbc.query("""
            SELECT p.id FROM lesson_source_preview p
            WHERE p.video_id=:video AND p.start_ms=:start AND p.end_ms=:end
              ORDER BY p.created_at DESC LIMIT 10
            """,args,(rs,n)->rs.getObject(1,UUID.class));
        for(UUID candidate:cached) {
            var preview=get(candidate);
            if(Set.of("QUEUED","RUNNING","RETRY_WAIT","SUCCEEDED").contains(preview.status())) return preview;
        }
        UUID id=UUID.randomUUID();
        transactions.executeWithoutResult(tx->{
            UUID job=jobs.enqueue(jobType(),"lesson",id,50,3,"{}");args.put("id",id);args.put("job",job);
            jdbc.update("INSERT INTO lesson_source_preview(id,video_id,start_ms,end_ms,job_id) VALUES(:id,:video,:start,:end,:job)",args);
        });
        return get(id);
    }
    private Map<String,Object> row(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM lesson_source_preview WHERE id=:id",Map.of("id",id));
        if(rows.isEmpty()) throw new ResourceNotFoundException("Source preview not found");return rows.getFirst();
    }
    @Override public Preview get(UUID id) {
        var row=row(id);var job=jobs.findById((UUID)row.get("job_id")).orElseThrow();
        List<Double> peaks=row.get("peaks")==null ? List.of() : mapper.readValue(row.get("peaks").toString(),
            mapper.getTypeFactory().constructCollectionType(List.class,Double.class));
        return new Preview(id,job.id(),job.status().name(),job.currentStep(),job.errorMessage(),
            ((Number)row.get("start_ms")).longValue(),((Number)row.get("end_ms")).longValue(),peaks,
            row.get("audio_object_key")==null ? null : audioDuration((String)row.get("audio_object_key")));
    }
    private double audioDuration(String key) {
        try(var input=new java.io.BufferedInputStream(storage.get(key));var audio=AudioSystem.getAudioInputStream(input)) {
            if(audio.getFrameLength()<=0 || audio.getFormat().getFrameRate()<=0)
                throw new IllegalStateException("Prepared audio has no measured duration");
            return audio.getFrameLength()*1000.0/audio.getFormat().getFrameRate();
        } catch(java.io.IOException | javax.sound.sampled.UnsupportedAudioFileException failure) {
            throw new IllegalStateException("Cannot inspect prepared audio",failure);
        }
    }
    @Override public byte[] audio(UUID id) {
        var row=row(id);if(row.get("audio_object_key")==null) throw new StateConflictException("Preview is not ready");
        try(var input=storage.get((String)row.get("audio_object_key"))) {return input.readAllBytes();}
        catch(java.io.IOException failure) {throw new IllegalStateException("Cannot read prepared audio",failure);}
    }
    @Override public void execute(BackgroundJob job,JobExecutionContext context) throws Exception {
        UUID id=job.ownerReferenceId();var row=row(id);
        if(row.get("audio_object_key")!=null) return;
        String video=(String)row.get("video_id");
        context.progress("PREPARING_SELECTED_RANGE",0);
        String key;
        try {key=source.prepare("https://www.youtube.com/watch?v="+video,video,
            ((Number)row.get("start_ms")).longValue(),((Number)row.get("end_ms")).longValue());}
        catch(com.lyreo.lesson.application.build.SourceMaterializationException failure) {
            if(!failure.retryable()) throw new PermanentJobException(failure.errorCode(),failure.getMessage());
            throw failure;
        }
        context.throwIfCancellationRequested();context.progress("COMPUTING_WAVEFORM",0);
        byte[] wav;try(var input=storage.get(key)) {wav=input.readAllBytes();}
        var peaks=peaks(wav);context.throwIfCancellationRequested();
        transactions.executeWithoutResult(tx->{
            jobs.fenceForCommit(job.id(),job.leaseOwner());
            jdbc.update("UPDATE lesson_source_preview SET audio_object_key=:key,peaks=CAST(:peaks AS jsonb) WHERE id=:id AND job_id=:job",
                Map.of("id",id,"job",job.id(),"key",key,"peaks",mapper.writeValueAsString(peaks)));
        });
    }
    public static List<Double> peaks(byte[] wav) throws Exception {
        try(var audio=AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            var format=audio.getFormat();
            if(format.getChannels()!=1 || format.getSampleSizeInBits()!=16 || format.isBigEndian())
                throw new RequestValidationException("Waveform requires mono little-endian PCM16");
            byte[] pcm=audio.readAllBytes();int samples=pcm.length/2;
            if(samples==0) throw new RequestValidationException("Prepared audio is empty");
            int stride=Math.max(1,(int)Math.ceil(samples/12_000.0));var result=new ArrayList<Double>();
            for(int begin=0;begin<samples;begin+=stride) {
                int peak=0;
                for(int i=begin;i<Math.min(samples,begin+stride);i++) {
                    short value=(short)((pcm[2*i]&255)|(pcm[2*i+1]<<8));peak=Math.max(peak,Math.abs((int)value));
                }
                result.add(peak/32768.0);
            }
            return result;
        }
    }
}
