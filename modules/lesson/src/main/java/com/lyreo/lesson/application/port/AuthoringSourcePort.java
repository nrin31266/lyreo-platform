package com.lyreo.lesson.application.port;

/** Acquisition is owned by Core; AI receives prepared audio, never a YouTube URL. */
public interface AuthoringSourcePort {
    SourceInfo inspect(String sourceReference);
    String prepare(String sourceReference, String videoId, long startMs, long endMs);

    record SourceInfo(String videoId, String title, long durationMs, boolean embedAllowed, String thumbnailUrl) {
        public SourceInfo(String videoId,String title,long durationMs,boolean embedAllowed) {this(videoId,title,durationMs,embedAllowed,null);}
    }
}
