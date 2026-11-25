package com.lyreo.platform.content;

import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.contracts.errors.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssetStore {
    private final NamedParameterJdbcTemplate jdbc;private final ObjectStoragePort storage;private final ObjectMapper json;
    public AssetStore(NamedParameterJdbcTemplate jdbc,ObjectStoragePort storage,ObjectMapper json) {this.jdbc=jdbc;this.storage=storage;this.json=json;}
    public record Asset(UUID id,String mimeType,long sizeBytes,String filename,boolean synthetic) {}
    public record AssetSummary(UUID id,String mimeType,long sizeBytes,String filename,boolean synthetic,String createdBy,Instant createdAt) {}
    public Asset get(UUID id) {return jdbc.query("SELECT id,mime_type,size_bytes,filename,synthetic FROM content_asset WHERE id=:id",Map.of("id",id),
        (rs,n)->new Asset(id,rs.getString(2),rs.getLong(3),rs.getString(4),rs.getBoolean(5))).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Media not found"));}
    public CatalogPage<AssetSummary> list(int page,int size,String query,String kind) {
        int clampedPage=Math.max(0,page);
        int clampedSize=Math.max(1,Math.min(size,100));
        String pattern=query==null||query.isBlank()?"%":"%"+query.trim().toLowerCase(Locale.ROOT)+"%";
        String mimePrefix=switch(kind==null?"":kind.trim().toLowerCase(Locale.ROOT)) {
            case "audio" -> "audio/%";
            case "image" -> "image/%";
            default -> "%";
        };
        var params=Map.<String,Object>of("pattern",pattern,"mime",mimePrefix,"limit",clampedSize,"offset",(long)clampedPage*clampedSize);
        Long total=jdbc.queryForObject(
            "SELECT count(*) FROM content_asset WHERE lower(filename) LIKE :pattern AND mime_type LIKE :mime",
            params,
            Long.class
        );
        List<AssetSummary> items=jdbc.query(
            "SELECT id,mime_type,size_bytes,filename,synthetic,created_by,created_at FROM content_asset "
                +"WHERE lower(filename) LIKE :pattern AND mime_type LIKE :mime "
                +"ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
            params,
            (rs,n)->new AssetSummary(
                rs.getObject("id",UUID.class),
                rs.getString("mime_type"),
                rs.getLong("size_bytes"),
                rs.getString("filename"),
                rs.getBoolean("synthetic"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant()
            )
        );
        return new CatalogPage<>(items,total==null?0L:total,clampedPage,clampedSize);
    }
    public boolean exists(UUID id) {try{return storage.exists(key(id));}catch(ResourceNotFoundException missing){return false;}}
    public Map<UUID,Asset> available(Collection<UUID> ids) {
        if(ids.isEmpty()) return Map.of();
        var executor=Executors.newFixedThreadPool(8);
        try {
            var checks=ids.stream().distinct().map(id->(Callable<Asset>)()->{
                try {var asset=get(id);return exists(id)?asset:null;}
                catch(ResourceNotFoundException missing) {return null;}
            }).toList();
            var result=new HashMap<UUID,Asset>();
            for(var future:executor.invokeAll(checks,20,TimeUnit.SECONDS)) {
                if(future.isCancelled()) throw new ServiceUnavailableException("MEDIA_CHECK_UNAVAILABLE","Media verification timed out; retry the test check",null);
                var asset=future.get();if(asset!=null) result.put(asset.id(),asset);
            }
            return result;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new ServiceUnavailableException("MEDIA_CHECK_UNAVAILABLE","Media verification interrupted; retry the test check",interrupted);
        } catch(ExecutionException failure) {
            throw new ServiceUnavailableException("MEDIA_CHECK_UNAVAILABLE","Media storage could not be checked; retry the test check",failure.getCause());
        } finally {executor.shutdownNow();}
    }
    public byte[] bytes(UUID id) {try(var input=storage.get(key(id))){return input.readAllBytes();}catch(java.io.IOException|IllegalStateException failure){throw new ResourceNotFoundException("Media file unavailable");}}
    private String key(UUID id) {var rows=jdbc.query("SELECT storage_key FROM content_asset WHERE id=:id",Map.of("id",id),(rs,n)->rs.getString(1));if(rows.isEmpty()) throw new ResourceNotFoundException("Media not found");return rows.getFirst();}
    public Asset upload(String filename,byte[] bytes,boolean synthetic,String actor) {
        if(bytes.length==0 || bytes.length>30*1024*1024) throw new RequestValidationException("Choose a file up to 30 MiB");
        String name=filename==null?"upload":filename.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]","");
        String mime=detect(bytes);
        if(mime.startsWith("image/") && bytes.length>10*1024*1024) throw new RequestValidationException("Images must be at most 10 MiB");
        probe(bytes,mime);
        try {
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String key="media/sha256/"+sha.substring(0,2)+"/"+sha;
            if(!storage.exists(key)) storage.put(key,mime,bytes);
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO content_asset(id,sha256,storage_key,mime_type,size_bytes,filename,synthetic,created_by) VALUES(:id,:sha,:key,:mime,:size,:name,:synthetic,:actor)",Map.of("id",id,"sha",sha,"key",key,"mime",mime,"size",bytes.length,"name",name,"synthetic",synthetic,"actor",actor));
            return get(id);
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String detect(byte[] b) {
        if(b.length>=12) {
            if(b[0]==(byte)0x89 && b[1]=='P' && b[2]=='N' && b[3]=='G') return "image/png";
            if(b[0]==(byte)0xff && b[1]==(byte)0xd8 && b[2]==(byte)0xff) return "image/jpeg";
            String head=new String(b,0,12,java.nio.charset.StandardCharsets.ISO_8859_1);
            if(head.startsWith("RIFF") && head.endsWith("WEBP")) return "image/webp";
            if(head.startsWith("RIFF") && head.endsWith("WAVE")) return "audio/wav";
            if(head.substring(4,8).equals("ftyp")) return "audio/mp4";
            if(head.startsWith("ID3") || b[0]==(byte)0xff && (b[1]&0xe0)==0xe0) return "audio/mpeg";
        }
        throw new RequestValidationException("Supported files: MP3, WAV, M4A, JPEG, PNG, WebP");
    }
    private void probe(byte[] bytes,String mime) {
        Path file=null,output=null;
        try {
            file=Files.createTempFile("lyreo-media-",".bin");output=Files.createTempFile("lyreo-probe-",".json");Files.write(file,bytes);
            var process=new ProcessBuilder("ffprobe","-v","error","-protocol_whitelist","file,pipe","-show_streams","-show_format","-of","json",file.toString()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(30,TimeUnit.SECONDS)) {process.destroyForcibly();throw new RequestValidationException("Media verification timed out");}
            if(process.exitValue()!=0) throw new RequestValidationException("Media cannot be decoded");
            var info=json.readTree(Files.readString(output));boolean found=false;
            for(var stream:info.path("streams")) {
                String type=stream.path("codec_type").asText();
                if(mime.startsWith("audio/") && type.equals("video")) throw new RequestValidationException("Choose an audio file without video");
                if(mime.startsWith("audio/") && type.equals("audio")) found=true;
                if(mime.startsWith("image/") && type.equals("video")) {
                    long pixels=stream.path("width").asLong()*stream.path("height").asLong();
                    if(pixels<=0 || pixels>40_000_000) throw new RequestValidationException("Image dimensions exceed the supported size");found=true;
                }
            }
            if(!found) throw new RequestValidationException("Media contains no supported stream");
            var decoder=new ProcessBuilder("ffmpeg","-nostdin","-v","error","-xerror","-threads","2","-protocol_whitelist","file,pipe","-i",file.toString(),"-map",mime.startsWith("audio/")?"0:a:0":"0:v:0","-f","null","-")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!decoder.waitFor(30,TimeUnit.SECONDS)) {decoder.destroyForcibly();throw new RequestValidationException("Media verification timed out");}
            if(decoder.exitValue()!=0) throw new RequestValidationException("Media cannot be decoded");
        } catch(java.io.IOException failure){throw new ServiceUnavailableException("MEDIA_TOOLS_UNAVAILABLE","Media verification requires FFmpeg and ffprobe",failure);}
          catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        finally {try{if(file!=null) Files.deleteIfExists(file);if(output!=null) Files.deleteIfExists(output);}catch(java.io.IOException ignored){}}
    }
}
