package com.lyreo.lesson.infrastructure.integration;

import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionLine;
import com.lyreo.lesson.application.port.AuthoringSourcePort.CaptionTrack;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.lesson.domain.authoring.CaptionCueText;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class YoutubeEnglishCaptions {
    private static final Pattern ENGLISH = Pattern.compile("(?i)en(?:-[a-z0-9]+)*");

    private YoutubeEnglishCaptions() {}

    static boolean isEnglish(String language) {
        return language != null && language.length() <= 64 && ENGLISH.matcher(language).matches();
    }

    static CaptionTrack inspect(JsonNode metadata) {
        var manual = select(metadata.path("subtitles"), false);
        return manual != null ? manual : select(metadata.path("automatic_captions"), true);
    }

    private static CaptionTrack select(JsonNode tracks, boolean automatic) {
        return tracks.properties().stream().filter(track -> isEnglish(track.getKey()))
            .filter(track -> track.getValue().valueStream().filter(format -> "json3".equals(format.path("ext").asText()))
                .reduce((first, last) -> last).filter(format -> isUsableFormat(format, automatic)).isPresent())
            .map(track -> new CaptionTrack(track.getKey(), automatic))
            .min(Comparator.<CaptionTrack>comparingInt(track -> track.language().equalsIgnoreCase(automatic ? "en-orig" : "en") ? 0 : 1)
                .thenComparing(CaptionTrack::language)).orElse(null);
    }

    static boolean isUsableFormat(JsonNode format, boolean automatic) {
        return "json3".equals(format.path("ext").asText())
            && (!automatic || isOriginalEnglish(format.path("url").asText()));
    }

    private static boolean isOriginalEnglish(String address) {
        try {
            String query = URI.create(address).getRawQuery();
            if (query == null) return false;
            String language = null;
            for (String parameter : query.split("&")) {
                String[] pair = parameter.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                if ("tlang".equals(key)) return false;
                if ("lang".equals(key)) {
                    if (language != null || pair.length != 2) return false;
                    language = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                }
            }
            return isEnglish(language);
        } catch (IllegalArgumentException malformed) { return false; }
    }

    static List<CaptionLine> lines(JsonNode document, long clipStartMs, long clipEndMs) {
        if (clipStartMs < 0 || clipEndMs <= clipStartMs || clipEndMs > 7_200_000
            || clipEndMs - clipStartMs > 600_000 || document == null || !document.path("events").isArray()) {
            throw unavailable("The English caption track or selected range is invalid");
        }
        var cues = new ArrayList<CaptionLine>();
        for (JsonNode event : document.path("events")) {
            var text = new StringBuilder();
            for (JsonNode segment : event.path("segs")) text.append(segment.path("utf8").asText(""));
            String line = CaptionCueText.speech(text.toString());
            if (line.length() > 10_000) throw unavailable("The English caption track has an oversized cue");
            if (AuthoringText.tokens(line).isEmpty()) continue;
            long start = millis(event.path("tStartMs"));
            long duration = millis(event.path("dDurationMs"));
            if (start < 0 || start > 7_200_000 || duration <= 0 || duration > 7_200_000)
                throw unavailable("The English caption track has invalid cue text or timing");
            cues.add(new CaptionLine(line, start, start + duration));
        }
        cues.sort(Comparator.comparingLong(CaptionLine::startMs));
        var result = new ArrayList<CaptionLine>();
        int transcriptLength = 0;
        for (int i = 0; i < cues.size(); i++) {
            var cue = cues.get(i);
            long end = i + 1 < cues.size() ? Math.min(cue.endMs(), cues.get(i + 1).startMs()) : cue.endMs();
            if ((end <= clipStartMs && cue.startMs() < clipStartMs) || cue.startMs() >= clipEndMs) continue;
            if (cue.startMs() < clipStartMs || end > clipEndMs)
                throw unavailable("The selected range cuts through an English caption line");
            transcriptLength += cue.text().length() + 1;
            if (end <= cue.startMs() || end - cue.startMs() > 115_000 || result.size() == 1000
                || transcriptLength > 200_001)
                throw unavailable("The English caption track cannot be aligned safely");
            // Rolling captions stay on screen after the next cue starts. Use the next onset as the bound.
            result.add(new CaptionLine(cue.text(), cue.startMs() - clipStartMs, end - clipStartMs));
        }
        if (result.isEmpty()) throw unavailable("No usable English caption lines are available in the selected range");
        return List.copyOf(result);
    }

    private static long millis(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : -1;
    }

    static SourceMaterializationException unavailable(String message) {
        return new SourceMaterializationException("YOUTUBE_CAPTIONS_UNAVAILABLE", message, false);
    }
}
