package com.lyreo.lesson.application.port;

import java.util.List;
import java.util.Map;

/** Acquisition is owned by Core; AI receives prepared audio, never a YouTube URL. */
public interface AuthoringSourcePort {
    SourceInfo inspect(String sourceReference);
    String prepare(String sourceReference, String videoId, long startMs, long endMs);
    List<CaptionLine> englishCaptions(String videoId, CaptionTrack track, long startMs, long endMs);

    record SourceInfo(String videoId, String title, long durationMs, boolean embedAllowed,
                      String thumbnailUrl, CaptionTrack englishCaptions) {
        public SourceInfo(String videoId,String title,long durationMs,boolean embedAllowed) {this(videoId,title,durationMs,embedAllowed,null,null);}
    }

    record CaptionTrack(String language, boolean automatic) {
        public String transcriptSource() { return automatic ? "YOUTUBE_EN_AUTOMATIC" : "YOUTUBE_EN_MANUAL"; }

        public Map<String,Object> evidence() { return Map.of("captionLanguage",language,"captionAutomatic",automatic); }

        public static CaptionTrack fromEvidence(Map<String,Object> evidence) {
            return evidence.get("captionLanguage") instanceof String language
                ? new CaptionTrack(language, Boolean.TRUE.equals(evidence.get("captionAutomatic"))) : null;
        }
    }

    record CaptionLine(String text, long startMs, long endMs) {}
}
