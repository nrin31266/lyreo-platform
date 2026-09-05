package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.LessonSourceMaterializer;
import com.lyreo.lesson.application.SourceMaterializationException;
import com.lyreo.lesson.domain.Lesson;
import com.lyreo.lesson.domain.LessonSourceType;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * YouTube media materializer backed by the external {@code yt-dlp} command.
 *
 * <p>Lyreo keeps this behind a port because source ingestion is product/infrastructure logic,
 * not AI inference. The learner-facing product can still play YouTube through the official
 * player; this artifact exists only to make ASR/alignment practical for the configured product
 * assumption.</p>
 */
public final class ExternalCommandYoutubeSourceMaterializer implements LessonSourceMaterializer {
    private final ObjectStoragePort storage;
    private final boolean enabled;
    private final String command;
    private final Duration timeout;

    public ExternalCommandYoutubeSourceMaterializer(
        ObjectStoragePort storage,
        boolean enabled,
        String command,
        Duration timeout
    ) {
        this.storage = storage;
        this.enabled = enabled;
        this.command = command;
        this.timeout = timeout;
    }

    @Override
    public Optional<String> materialize(Lesson lesson) {
        if (lesson.sourceType() != LessonSourceType.YOUTUBE) {
            return Optional.empty();
        }
        if (lesson.canonicalAudioObjectKey() != null
            && !lesson.canonicalAudioObjectKey().isBlank()) {
            return Optional.of(lesson.canonicalAudioObjectKey());
        }
        if (!enabled) {
            throw new SourceMaterializationException(
                "YOUTUBE_MEDIA_TOOL_DISABLED",
                "YouTube source processing is disabled. Enable lyreo.lesson.youtube.enabled "
                    + "and install yt-dlp + ffmpeg, or use an AUDIO/TEXT source.",
                false
            );
        }
        if (lesson.sourceReference() == null || lesson.sourceReference().isBlank()) {
            throw new SourceMaterializationException(
                "YOUTUBE_SOURCE_MISSING",
                "YouTube lesson has no source URL",
                false
            );
        }

        Path work = null;
        try {
            work = Files.createTempDirectory("lyreo-youtube-");
            Path log = work.resolve("yt-dlp.log");
            String template = work.resolve("source.%(ext)s").toString();

            Process process = new ProcessBuilder(
                command,
                "--no-playlist",
                "--no-progress",
                "--extract-audio",
                "--audio-format",
                "m4a",
                "--audio-quality",
                "0",
                "--output",
                template,
                lesson.sourceReference()
            )
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new SourceMaterializationException(
                    "YOUTUBE_MEDIA_TIMEOUT",
                    "yt-dlp did not finish within " + timeout.toSeconds() + " seconds",
                    true
                );
            }
            if (process.exitValue() != 0) {
                throw new SourceMaterializationException(
                    "YOUTUBE_MEDIA_FAILED",
                    "yt-dlp failed with exit code " + process.exitValue() + ": "
                        + tail(log, 2_000),
                    true
                );
            }

            Path audio = findAudio(work, log).orElseThrow(() ->
                new SourceMaterializationException(
                    "YOUTUBE_AUDIO_NOT_FOUND",
                    "yt-dlp completed but produced no audio artifact",
                    true
                )
            );
            byte[] bytes = Files.readAllBytes(audio);
            String extension = extension(audio.getFileName().toString());
            String objectKey = "lessons/" + lesson.id() + "/source/youtube." + extension;
            storage.put(objectKey, contentType(extension), bytes);
            return Optional.of(objectKey);
        } catch (SourceMaterializationException known) {
            throw known;
        } catch (IOException io) {
            throw new SourceMaterializationException(
                "YOUTUBE_MEDIA_TOOL_UNAVAILABLE",
                "Unable to execute " + command + ". Install yt-dlp/ffmpeg or disable YouTube "
                    + "processing for this environment.",
                false,
                io
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SourceMaterializationException(
                "YOUTUBE_MEDIA_INTERRUPTED",
                "YouTube media materialization was interrupted",
                true,
                interrupted
            );
        } finally {
            deleteTree(work);
        }
    }

    private static Optional<Path> findAudio(Path directory, Path log) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> !path.equals(log))
                .filter(path -> !path.getFileName().toString().endsWith(".part"))
                .findFirst();
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "m4a";
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "m4a", "mp4" -> "audio/mp4";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }

    private static String tail(Path path, int maxChars) {
        try {
            String text = Files.exists(path) ? Files.readString(path) : "";
            if (text.length() <= maxChars) return text.strip();
            return text.substring(text.length() - maxChars).strip();
        } catch (IOException ignored) {
            return "<log unavailable>";
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary cleanup must not mask the build result.
                }
            });
        } catch (IOException ignored) {
            // Best effort for process temp files.
        }
    }
}
