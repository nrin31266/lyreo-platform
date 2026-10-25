package com.lyreo.lesson.infrastructure.integration;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Full-source cache is the conservative baseline; only the selected PCM range reaches a model. */
public final class AuthoringYoutubeSource implements AuthoringSourcePort {
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String command;
    private final Duration timeout;

    public AuthoringYoutubeSource(NamedParameterJdbcTemplate jdbc, ObjectStoragePort storage,
        ObjectMapper mapper, boolean enabled, String command, Duration timeout) {
        this.jdbc = jdbc; this.storage = storage; this.mapper = mapper;
        this.enabled = enabled; this.command = command; this.timeout = timeout;
    }

    public static String videoId(String source) {
        try {
            URI uri = URI.create(source == null ? "" : source.strip());
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) {
                throw new IllegalArgumentException();
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            String id = null;
            if (host.equals("youtu.be") || host.equals("www.youtu.be")) {
                id = path.startsWith("/") ? path.substring(1) : "";
            } else if (List.of("youtube.com", "www.youtube.com", "m.youtube.com",
                "youtube-nocookie.com", "www.youtube-nocookie.com").contains(host)) {
                if (path.equals("/watch") && uri.getRawQuery() != null) {
                    for (String entry : uri.getRawQuery().split("&")) {
                        String[] pair = entry.split("=", 2);
                        if (pair[0].equals("v") && pair.length == 2) {
                            if (id != null) throw new IllegalArgumentException();
                            id = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                        }
                    }
                } else if (path.matches("/(embed|shorts|live)/[^/]+")) {
                    id = path.substring(path.lastIndexOf('/') + 1);
                }
            }
            if (id == null || !VIDEO_ID.matcher(id).matches()) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new RequestValidationException("A valid YouTube video URL is required");
        }
    }

    @Override
    public SourceInfo inspect(String sourceReference) {
        String id = videoId(sourceReference);
        requireEnabled();
        var cached = jdbc.query("""
            SELECT video_id, title, duration_ms, embed_allowed FROM lesson_youtube_source
             WHERE video_id=:id AND updated_at > now() - interval '1 day'
            """, Map.of("id", id), (rs, row) -> new SourceInfo(rs.getString(1), rs.getString(2),
                rs.getLong(3), rs.getBoolean(4)));
        if (!cached.isEmpty()) return cached.getFirst();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new RequestValidationException("Inspect the source before creating a draft; metadata is missing or expired");
        }
        Path work = temp();
        try {
            Path result = work.resolve("metadata.json");
            run(List.of(command, "--no-playlist", "--no-warnings", "--skip-download",
                "--dump-single-json", "--", url(id)), result);
            var node = mapper.readTree(Files.readString(result));
            double seconds = node.path("duration").asDouble(0);
            if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 7200
                || node.path("is_live").asBoolean(false)) {
                throw new RequestValidationException("Choose a completed video up to two hours long");
            }
            SourceInfo info = new SourceInfo(id, node.path("title").asText(id),
                Math.round(seconds * 1000), node.path("playable_in_embed").asBoolean(true));
            jdbc.update("""
                INSERT INTO lesson_youtube_source(video_id,title,duration_ms,embed_allowed)
                VALUES (:id,:title,:duration,:embed)
                ON CONFLICT(video_id) DO UPDATE SET title=excluded.title,
                  duration_ms=excluded.duration_ms,embed_allowed=excluded.embed_allowed,updated_at=now()
                """, Map.of("id", id, "title", info.title(), "duration", info.durationMs(),
                    "embed", info.embedAllowed()));
            return info;
        } catch (IOException failure) {
            throw unavailable(failure);
        } finally { cleanup(work); }
    }

    @Override
    public String prepare(String sourceReference, String videoId, long startMs, long endMs) {
        requireEnabled();
        if (!videoId(sourceReference).equals(videoId)) {
            throw new RequestValidationException("Source identity does not match the video");
        }
        SourceInfo info = inspect(sourceReference);
        if (startMs < 0 || endMs <= startMs || endMs > info.durationMs() || endMs - startMs > 1_800_000) {
            throw new RequestValidationException("Choose a valid segment of at most 30 minutes");
        }
        Map<String, Object> args = Map.of("id", videoId, "start", startMs, "end", endMs);
        var cached = jdbc.queryForList("""
            SELECT audio_object_key FROM lesson_youtube_segment WHERE video_id=:id
              AND start_ms=:start AND end_ms=:end AND normalization_version='pcm16-v1'
            """, args, String.class);
        if (!cached.isEmpty()) return cached.getFirst();
        Path work = temp();
        try {
            Path source = work.resolve("source.m4a");
            var sourceKeys = jdbc.queryForList("SELECT audio_object_key FROM lesson_youtube_source "
                + "WHERE video_id=:id", Map.of("id", videoId), String.class);
            String sourceKey = sourceKeys.isEmpty() ? null : sourceKeys.getFirst();
            if (sourceKey == null) {
                run(List.of(command, "--no-playlist", "--no-progress", "--max-filesize", "256M",
                    "--extract-audio", "--audio-format", "m4a", "--output", source.toString(),
                    "--", url(videoId)), work.resolve("acquisition.log"));
                if (!Files.isRegularFile(source) || Files.size(source) > MAX_SOURCE_BYTES) {
                    throw new RequestValidationException("Source audio exceeds the supported size");
                }
                sourceKey = "sources/youtube/" + videoId + "/original.m4a";
                storage.put(sourceKey, "audio/mp4", Files.readAllBytes(source));
                jdbc.update("UPDATE lesson_youtube_source SET audio_object_key=:key WHERE video_id=:id",
                    Map.of("key", sourceKey, "id", videoId));
            } else {
                try (var input = storage.get(sourceKey)) { Files.copy(input, source); }
            }
            Path segment = work.resolve("segment.wav");
            run(List.of("ffmpeg", "-v", "error", "-nostdin", "-y", "-ss", seconds(startMs),
                "-i", source.toString(), "-t", seconds(endMs - startMs), "-vn", "-ar", "16000",
                "-ac", "1", "-c:a", "pcm_s16le", segment.toString()), work.resolve("ffmpeg.log"));
            String key = "sources/youtube/" + videoId + "/segments/" + startMs + "-" + endMs + "-pcm16-v1.wav";
            storage.put(key, "audio/wav", Files.readAllBytes(segment));
            jdbc.update("""
                INSERT INTO lesson_youtube_segment(video_id,start_ms,end_ms,normalization_version,audio_object_key)
                VALUES (:id,:start,:end,'pcm16-v1',:key) ON CONFLICT DO NOTHING
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(args).addValue("key", key));
            return key;
        } catch (IOException failure) { throw unavailable(failure); }
        finally { cleanup(work); }
    }

    private void run(List<String> args, Path log) throws IOException {
        Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(log.toFile()).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new SourceMaterializationException("YOUTUBE_MEDIA_TIMEOUT", "Media command timed out", true);
            }
            if (process.exitValue() != 0) {
                throw new SourceMaterializationException("YOUTUBE_MEDIA_FAILED",
                    "Media command failed; verify source availability and operator tools", true);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly(); Thread.currentThread().interrupt();
            throw new SourceMaterializationException("YOUTUBE_MEDIA_INTERRUPTED", "Media preparation interrupted", true);
        }
    }

    private void requireEnabled() {
        if (!enabled) throw new SourceMaterializationException("YOUTUBE_MEDIA_TOOL_DISABLED",
            "YouTube acquisition is disabled; an operator must verify media rights and enable it", false);
    }
    private static String url(String id) { return "https://www.youtube.com/watch?v=" + id; }
    private static String seconds(long millis) { return java.math.BigDecimal.valueOf(millis, 3).toPlainString(); }
    private static Path temp() {
        try { return Files.createTempDirectory("lyreo-authoring-"); }
        catch (IOException failure) { throw unavailable(failure); }
    }
    private static SourceMaterializationException unavailable(IOException failure) {
        return new SourceMaterializationException("YOUTUBE_MEDIA_TOOL_UNAVAILABLE",
            "Media preparation needs yt-dlp, FFmpeg and working object storage", false, failure);
    }
    private static void cleanup(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException ignored) { /* Cleanup must not replace the acquisition error. */ }
    }
}
