package com.lyreo.lesson.infrastructure.integration;

import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lesson.application.port.AuthoringMediaPort;
import com.lyreo.lesson.application.port.VoiceSamplesPort;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.sound.sampled.AudioSystem;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
public class VoiceSampleLibrary implements VoiceSamplesPort, JobHandler {
    public static final String TEXT="Every day is a chance to learn something new. Take a deep breath, listen carefully, and practise a little English with me.";
    public static final String TYPE="VOICE_SAMPLE_PREPARE";
    private static final UUID OWNER=UUID.nameUUIDFromBytes(TYPE.getBytes(StandardCharsets.UTF_8));
    private static final String PREFIX="reference/voices/kokoro-v1.0-"+hash((TEXT+"|1.0|0.9.4").getBytes(StandardCharsets.UTF_8))+"/";
    private final AuthoringMediaPort media;
    private final ObjectStoragePort storage;
    private final AiInvocationService ai;
    private final BackgroundJobService jobs;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    public VoiceSampleLibrary(AuthoringMediaPort media,ObjectStoragePort storage,AiInvocationService ai,BackgroundJobService jobs,
        NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper mapper) {
        this.media=media;this.storage=storage;this.ai=ai;this.jobs=jobs;this.jdbc=jdbc;
        this.transactions=new TransactionTemplate(manager);this.mapper=mapper;
    }
    @Override public String jobType() {return TYPE;}
    @Override public Catalog catalog() {
        var voices=media.voices();var manifest=manifest();
        var job=jobs.latest(TYPE,"lesson",OWNER).orElse(null);
        UUID id=job==null ? null : job.id();
        return new Catalog(voices.defaultVoice(),voices.voices().stream().map(voice->
            new Voice(voice.id(),voice.accent(),voice.name(),voice.gender(),manifest.containsKey(voice.id()) && storage.exists(key(voice.id())))).toList(),
            TEXT,id,job==null ? null : job.status().name(),job==null ? 0 : job.progressPercent(),job==null ? null : job.errorMessage());
    }
    @Override public UUID prepare() {
        return transactions.execute(tx->{
            jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(78231011)");
            var job=jobs.latest(TYPE,"lesson",OWNER).orElse(null);
            if(job!=null && Set.of("QUEUED","RUNNING","RETRY_WAIT","CANCEL_REQUESTED").contains(job.status().name())) return job.id();
            return jobs.enqueue(TYPE,"lesson",OWNER,10,3,"{}");
        });
    }
    @Override public void execute(BackgroundJob job,JobExecutionContext context) {
        var voices=media.voices().voices();var manifest=manifest();int completed=0;
        for(var voice:voices) {
            context.throwIfCancellationRequested();context.heartbeat();
            String key=key(voice.id());
            if(manifest.containsKey(voice.id()) && storage.exists(key) && valid(read(key),manifest.get(voice.id()))) {
                context.progress("VOICE_SAMPLES",++completed*100/voices.size());continue;
            }
            try {
                var result=ai.executeForProvider(AiCapability.TTS,"LOCAL_KOKORO","",Map.of("text",TEXT),Map.of("voice",voice.id(),"speed",1.0));
                Object encoded=result.output().get("audio_base64");
                if(!(encoded instanceof String text) || text.length()>4_000_000) throw new PermanentJobException("VOICE_SAMPLE_INVALID","Speech preview returned invalid audio");
                byte[] audio;
                try {audio=Base64.getDecoder().decode(text);} catch(IllegalArgumentException invalid) {throw new PermanentJobException("VOICE_SAMPLE_INVALID","Speech preview returned invalid audio");}
                if(!valid(audio,hash(audio))) throw new PermanentJobException("VOICE_SAMPLE_INVALID","Speech preview returned empty or unsupported audio");
                context.throwIfCancellationRequested();context.heartbeat();
                transactions.executeWithoutResult(tx->{
                    jobs.fenceForCommit(job.id(),job.leaseOwner());
                    storage.put(key,"audio/wav",audio);manifest.put(voice.id(),hash(audio));
                    storage.put(PREFIX+"manifest.json","application/json",mapper.writeValueAsBytes(Map.of("version",1,"library","0.9.4","model","hexgrad/Kokoro-82M v1.0","text",TEXT,"speed",1.0,"samples",manifest)));
                });
            } catch(AiExecutionException failure) {
                if(!failure.fallbackAllowed()) throw new PermanentJobException(failure.errorCode(),"Configure the local Kokoro TTS route and retry voice previews",failure);
                throw failure;
            }
            context.progress("VOICE_SAMPLES",++completed*100/voices.size());
        }
    }
    @Override public byte[] audio(String id) {
        var manifest=manifest();
        if(!manifest.containsKey(id) || !storage.exists(key(id))) throw new ResourceNotFoundException("Voice preview is not ready");
        byte[] audio=read(key(id));
        if(!valid(audio,manifest.get(id))) throw new ResourceNotFoundException("Voice preview needs to be regenerated");
        return audio;
    }
    private Map<String,String> manifest() {
        if(!storage.exists(PREFIX+"manifest.json")) return new LinkedHashMap<>();
        return new LinkedHashMap<>(mapper.convertValue(mapper.readTree(read(PREFIX+"manifest.json")).get("samples"),mapper.getTypeFactory().constructMapType(Map.class,String.class,String.class)));
    }
    private byte[] read(String key) {
        try(var input=storage.get(key)) {return input.readAllBytes();}
        catch(IOException failure) {throw new IllegalStateException("Unable to read voice preview",failure);}
    }
    private static String key(String id) {
        if(id==null || !id.matches("(?:af|am|bf|bm)_[a-z]+")) throw new ResourceNotFoundException("Unknown voice");
        return PREFIX+id+".wav";
    }
    private static boolean valid(byte[] audio,String expectedHash) {
        if(!hash(audio).equals(expectedHash)) return false;
        try(var input=AudioSystem.getAudioInputStream(new ByteArrayInputStream(audio))) {
            double duration=input.getFrameLength()/input.getFormat().getFrameRate();
            if(duration<=0 || duration>30) return false;
            if(!input.getFormat().getEncoding().equals(javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED)
                || input.getFormat().getSampleSizeInBits()!=16 || input.getFormat().isBigEndian()) return false;
            byte[] pcm=input.readAllBytes();
            for(int i=0;i+1<pcm.length;i+=2) if(Math.abs((short)((pcm[i]&255)|(pcm[i+1]<<8)))>8) return true;
            return false;
        } catch(Exception invalid) {return false;}
    }
    private static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
