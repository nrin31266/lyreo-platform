package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.UUID;

public interface AuthoringMediaPort {
    Source upload(String filename, byte[] content);
    Source source(UUID id);
    byte[] readSource(UUID id);
    byte[] readAudio(String key);
    String prepare(UUID sourceId, long startMs, long endMs);
    Audio storeTts(UUID lessonId, UUID jobId, byte[] content);
    Voices voices();
    record Source(UUID id, long durationMs, String contentType, String originalFilename) {
        public Source(UUID id,long durationMs,String contentType) {this(id,durationMs,contentType,null);}
    }
    record Audio(String key, long durationMs) {}
    record Voice(String id, String accent, String name, String gender) {
        public Voice(String id,String accent,String name) {this(id,accent,name,id.length()>1 && id.charAt(1)=='f' ? "FEMALE" : "MALE");}
    }
    record Voices(String defaultVoice, List<Voice> voices) {}
}
