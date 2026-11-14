package com.lyreo.lesson.infrastructure.integration;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import com.lyreo.lesson.application.port.AuthoringMediaPort;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Uploaded sources and generated speech stay private; only selected PCM reaches inference. */
public final class AuthoringMedia implements AuthoringMediaPort {
    public static final int MAX_UPLOAD_BYTES=30*1024*1024;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final ObjectMapper mapper;
    private final RestClient ai;

    public AuthoringMedia(NamedParameterJdbcTemplate jdbc,ObjectStoragePort storage,ObjectMapper mapper,String aiUrl,String token) {
        this.jdbc=jdbc;this.storage=storage;this.mapper=mapper;
        var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        ai=RestClient.builder().baseUrl(aiUrl).defaultHeader("X-Lyreo-Internal-Token",token).requestFactory(factory).build();
    }
    @Override public Source upload(String filename,byte[] content) {
        String extension=filename==null ? "" : filename.substring(filename.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
        require(List.of("mp3","wav","m4a").contains(extension),"Upload an MP3, WAV or M4A file");
        require(content.length>0 && content.length<=MAX_UPLOAD_BYTES,"Audio upload must contain 1–30 MiB");
        UUID id=UUID.randomUUID();String key="sources/audio/"+id+"/source."+extension;
        Path work=temp();
        try {
            Path file=work.resolve("source."+extension);Files.write(file,content);
            long duration=probe(file,work);
            require(duration>0 && duration<=7_200_000,"Choose completed audio up to two hours long");
            run(List.of("ffmpeg","-v","error","-xerror","-nostdin","-protocol_whitelist","file,pipe","-i",file.toString(),"-map","0:a:0","-f","null","-"),work.resolve("decode.log"));
            String type=switch(extension) {case "mp3"->"audio/mpeg";case "wav"->"audio/wav";default->"audio/mp4";};
            storage.put(key,type,content);
            try {
                jdbc.update("INSERT INTO lesson_audio_source(id,object_key,content_type,duration_ms,original_filename) VALUES(:id,:key,:type,:duration,:filename)",
                    Map.of("id",id,"key",key,"type",type,"duration",duration,"filename",safeFilename(filename)));
            } catch(RuntimeException failure) {
                try {storage.delete(key);} catch(RuntimeException cleanup) {failure.addSuppressed(cleanup);}
                throw failure;
            }
            return new Source(id,duration,type,safeFilename(filename));
        } catch(IOException failure) {throw unavailable(failure);}
        finally {cleanup(work);}
    }
    @Override public Source source(UUID id) {
        return jdbc.query("SELECT * FROM lesson_audio_source WHERE id=:id",Map.of("id",id),
            (rs,n)->new Source(id,rs.getLong("duration_ms"),rs.getString("content_type"),rs.getString("original_filename"))).stream().findFirst()
            .orElseThrow(()->new ResourceNotFoundException("Uploaded audio not found"));
    }
    private String sourceKey(UUID id) {
        source(id);
        return jdbc.queryForObject("SELECT object_key FROM lesson_audio_source WHERE id=:id",Map.of("id",id),String.class);
    }
    @Override public byte[] readSource(UUID id) {return readAudio(sourceKey(id));}
    @Override public byte[] readAudio(String key) {
        try(var input=storage.get(key)) {return input.readAllBytes();}
        catch(IOException failure) {throw unavailable(failure);}
    }
    @Override public String prepare(UUID sourceId,long startMs,long endMs) {
        var info=source(sourceId);
        require(startMs>=0 && endMs>startMs && endMs<=info.durationMs() && endMs-startMs<=600_000,"Select up to 10 minutes within the audio source");
        Path work=temp();
        try {
            Path source=work.resolve("source");
            try(var input=storage.get(sourceKey(sourceId))) {Files.copy(input,source);}
            Path target=work.resolve("segment.wav");
            normalize(source,target,startMs,endMs-startMs,work);
            String key="sources/audio/"+sourceId+"/"+startMs+"-"+endMs+".wav";
            storage.put(key,"audio/wav",Files.readAllBytes(target));return key;
        } catch(IOException failure) {throw unavailable(failure);}
        finally {cleanup(work);}
    }
    @Override public Audio storeTts(UUID lessonId,UUID jobId,byte[] content) {
        require(content.length>0 && content.length<=64*1024*1024,"Generated speech exceeds the supported size");
        Path work=temp();
        try {
            Path file=work.resolve("tts.wav");Files.write(file,content);
            long duration=probe(file,work);
            require(duration>0 && duration<=600_000,"Generated audio exceeds 10 minutes; shorten the text and retry");
            Path normalized=work.resolve("audio.wav");normalize(file,normalized,0,duration,work);
            String key="lessons/"+lessonId+"/tts/"+jobId+".wav";
            storage.put(key,"audio/wav",Files.readAllBytes(normalized));return new Audio(key,duration);
        } catch(IOException failure) {throw unavailable(failure);}
        finally {cleanup(work);}
    }
    @Override public Voices voices() {
        try {
            var data=mapper.readTree(ai.get().uri("/v1/tts/voices").retrieve().body(String.class));
            var voices=new java.util.ArrayList<Voice>();
            for(var voice:data.path("voices")) voices.add(new Voice(voice.path("voice_id").asText(),voice.path("accent").asText(),voice.path("display").path("name").asText()));
            return new Voices(data.path("default_voice").asText("af_heart"),List.copyOf(voices));
        } catch(RuntimeException failure) {
            throw new ServiceUnavailableException("TTS_VOICES_UNAVAILABLE","Voice list is unavailable; check AI service configuration and retry",failure);
        }
    }
    private long probe(Path source,Path work) throws IOException {
        Path output=work.resolve("probe.json");
        run(List.of("ffprobe","-v","error","-protocol_whitelist","file,pipe","-show_format","-show_streams","-of","json",source.toString()),output);
        var info=mapper.readTree(Files.readString(output));
        boolean audio=false;for(var stream:info.path("streams")) if("audio".equals(stream.path("codec_type").asText())) audio=true;
        require(audio,"The file contains no decodable audio");
        String container=info.path("format").path("format_name").asText();
        String name=source.getFileName().toString();
        require(name.endsWith(".mp3") ? container.equals("mp3") : name.endsWith(".wav") ? container.equals("wav")
            : name.endsWith(".m4a") && container.equals("mov,mp4,m4a,3gp,3g2,mj2"),"Audio container does not match MP3, WAV or M4A file type");
        double seconds=info.path("format").path("duration").asDouble(0);
        require(Double.isFinite(seconds) && seconds>0,"Audio duration could not be determined");
        return Math.round(seconds*1000);
    }
    private void normalize(Path source,Path target,long start,long duration,Path work) throws IOException {
        run(List.of("ffmpeg","-v","error","-nostdin","-y","-protocol_whitelist","file,pipe","-ss",seconds(start),"-i",source.toString(),"-t",seconds(duration),"-vn","-ar","16000","-ac","1","-c:a","pcm_s16le",target.toString()),work.resolve("normalize.log"));
    }
    private static String safeFilename(String filename) {
        String value=filename.replace('\\','/');value=value.substring(value.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","").strip();
        return value.length()>255 ? value.substring(0,255) : value;
    }
    private static String seconds(long ms) {return java.math.BigDecimal.valueOf(ms,3).toPlainString();}
    private static void run(List<String> args,Path output) throws IOException {
        var process=new ProcessBuilder(args).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!process.waitFor(120,TimeUnit.SECONDS)) {process.destroyForcibly();throw new RequestValidationException("Audio processing timed out; choose a smaller source");}
            require(process.exitValue()==0,"Audio cannot be decoded; choose a valid MP3, WAV or M4A file");
        } catch(InterruptedException failure) {process.destroyForcibly();Thread.currentThread().interrupt();throw unavailable(failure);}
    }
    private static Path temp() {try {return Files.createTempDirectory("lyreo-audio-");} catch(IOException failure) {throw unavailable(failure);}}
    private static void cleanup(Path root) {
        try(var paths=Files.walk(root)) {for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
        catch(IOException ignored) { /* Keep the original processing error. */ }
    }
    private static void require(boolean condition,String message) {if(!condition) throw new RequestValidationException(message);}
    private static ServiceUnavailableException unavailable(Exception failure) {
        return new ServiceUnavailableException("AUDIO_TOOLS_UNAVAILABLE","Audio preparation requires FFmpeg, ffprobe and working storage",failure);
    }
}
