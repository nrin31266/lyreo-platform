package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.infrastructure.integration.AuthoringYoutubeSource;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AuthoringYoutubeAcquisitionTest {
    private static final String URL = "https://www.youtube.com/watch?v=Abc_123-xYz";
    private static final String VIDEO_ID = "Abc_123-xYz";
    @TempDir Path temp;

    private NamedParameterJdbcTemplate jdbc;
    private ObjectStoragePort storage;
    private Map<String, byte[]> stored;
    private AtomicBoolean metadataSaved;
    private AtomicReference<String> sourceKey;
    private Map<String, String> segmentKeys;

    @BeforeEach void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        storage = mock(ObjectStoragePort.class);
        stored = new ConcurrentHashMap<>();
        metadataSaved = new AtomicBoolean();
        sourceKey = new AtomicReference<>();
        segmentKeys = new ConcurrentHashMap<>();
        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class))).thenAnswer(call -> metadataSaved.get()
            ? java.util.List.of(new AuthoringSourcePort.SourceInfo(VIDEO_ID, "Fixture video", 5_000, true))
            : java.util.List.of());
        when(jdbc.queryForList(anyString(), anyMap(), eq(String.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            @SuppressWarnings("unchecked") Map<String, Object> args = call.getArgument(1);
            if (sql.contains("lesson_youtube_segment")) {
                String key = segmentKeys.get(args.get("start") + ":" + args.get("end"));
                return key == null ? java.util.List.of() : java.util.List.of(key);
            }
            String key = sourceKey.get();
            return key == null ? java.util.List.of() : java.util.List.of(key);
        });
        when(jdbc.update(anyString(), anyMap())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            @SuppressWarnings("unchecked") Map<String, Object> args = call.getArgument(1);
            if (sql.contains("INSERT INTO lesson_youtube_source")) metadataSaved.set(true);
            if (sql.startsWith("UPDATE lesson_youtube_source")) sourceKey.set((String) args.get("key"));
            if (sql.contains("INSERT INTO lesson_youtube_segment")) {
                segmentKeys.put(args.get("start") + ":" + args.get("end"), (String) args.get("key"));
            }
            return 1;
        });
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenAnswer(call -> {
            MapSqlParameterSource args = call.getArgument(1);
            segmentKeys.put(args.getValue("start") + ":" + args.getValue("end"), (String) args.getValue("key"));
            return 1;
        });
        when(storage.put(anyString(), anyString(), any(byte[].class))).thenAnswer(call -> {
            String key = call.getArgument(0);
            stored.put(key, call.getArgument(2));
            return new ObjectStoragePort.StoredObject(key, stored.get(key).length, "fixture");
        });
        when(storage.get(anyString())).thenAnswer(call -> new ByteArrayInputStream(stored.get(call.getArgument(0))));
    }

    @Test void inspectsCachesSourceAndNormalizesSelectedRangesOnce(@TempDir Path work) throws Exception {
        Assumptions.assumeTrue(executable("ffmpeg"), "ffmpeg is required for media acquisition coverage");
        Path fixture = createFixture(work);
        Path calls = work.resolve("calls.log");
        Path downloader = fakeDownloader(work, fixture, calls);
        var source = source(downloader.toString());

        var info = source.inspect(URL);
        assertThat(info.videoId()).isEqualTo(VIDEO_ID);
        assertThat(info.title()).isEqualTo("Fixture video");
        assertThat(info.durationMs()).isEqualTo(5_000);
        assertThat(info.embedAllowed()).isTrue();

        String first = source.prepare(URL, VIDEO_ID, 500, 1_500);
        String second = source.prepare(URL, VIDEO_ID, 2_000, 3_500);
        assertThat(source.prepare(URL, VIDEO_ID, 500, 1_500)).isEqualTo(first);
        assertThat(first).isEqualTo("sources/youtube/" + VIDEO_ID + "/segments/500-1500-pcm16-v1.wav");
        assertThat(second).isEqualTo("sources/youtube/" + VIDEO_ID + "/segments/2000-3500-pcm16-v1.wav");

        byte[] wav = stored.get(first);
        assertThat(wav).isNotNull();
        WavDetails details = wavDetails(wav);
        assertThat(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(details.channels()).isEqualTo(1);
        assertThat(details.sampleRate()).isEqualTo(16_000);
        assertThat(details.durationSeconds()).isCloseTo(1.0, within(0.12));
        assertThat(Files.readAllLines(calls)).containsExactly("metadata", "audio");
        verify(storage, times(1)).put(eq("sources/youtube/" + VIDEO_ID + "/original.m4a"), eq("audio/mp4"), any());
        verify(storage, times(2)).put(startsWith("sources/youtube/" + VIDEO_ID + "/segments/"), eq("audio/wav"), any());
    }

    @Test void reportsDownloaderFailuresAndMissingToolWithoutLeakingOutput() throws Exception {
        Path failing = temp.resolve("failing-yt-dlp");
        Files.writeString(failing, "#!/bin/sh\necho 'credential=secret provider detail'\nexit 7\n");
        makeExecutable(failing);
        var failedSource = source(failing.toString());
        assertThatThrownBy(() -> failedSource.inspect(URL))
            .isInstanceOfSatisfying(SourceMaterializationException.class, error -> {
                assertThat(error.errorCode()).isEqualTo("YOUTUBE_MEDIA_FAILED");
                assertThat(error.getMessage()).doesNotContain("secret", "provider detail");
                assertThat(error.retryable()).isTrue();
            });

        var missingSource = source(temp.resolve("missing-yt-dlp").toString());
        assertThatThrownBy(() -> missingSource.inspect(URL))
            .isInstanceOfSatisfying(SourceMaterializationException.class, error -> {
                assertThat(error.errorCode()).isEqualTo("YOUTUBE_MEDIA_TOOL_UNAVAILABLE");
                assertThat(error.getMessage()).doesNotContain("secret", "provider detail");
                assertThat(error.retryable()).isFalse();
            });
    }

    private AuthoringYoutubeSource source(String command) {
        return new AuthoringYoutubeSource(jdbc, storage, new ObjectMapper(), true, command, Duration.ofSeconds(10));
    }

    private Path createFixture(Path work) throws Exception {
        Path fixture = work.resolve("fixture.m4a");
        Process process = new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "sine=frequency=440:duration=5", "-c:a", "aac", "-y", fixture.toString()).start();
        assertThat(process.waitFor()).isZero();
        return fixture;
    }

    private Path fakeDownloader(Path work, Path fixture, Path calls) throws IOException {
        Path script = work.resolve("fake-yt-dlp");
        String body = "#!/bin/sh\n"
            + "for arg in \"$@\"; do\n"
            + "  if [ \"$arg\" = \"--dump-single-json\" ]; then\n"
            + "    echo metadata >> " + quote(calls) + "\n"
            + "    printf '%s\\n' '{\"title\":\"Fixture video\",\"duration\":5,\"playable_in_embed\":true}'\n"
            + "    exit 0\n"
            + "  fi\n"
            + "done\n"
            + "echo audio >> " + quote(calls) + "\n"
            + "while [ $# -gt 0 ]; do\n"
            + "  if [ \"$1\" = \"--output\" ]; then shift; cp " + quote(fixture) + " \"$1\"; exit $?; fi\n"
            + "  shift\n"
            + "done\nexit 2\n";
        Files.writeString(script, body);
        makeExecutable(script);
        return script;
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "'\\''") + "'";
    }

    private static void makeExecutable(Path path) throws IOException {
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }

    private static boolean executable(String command) {
        return java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
            .map(Path::of).map(path -> path.resolve(command)).anyMatch(Files::isExecutable);
    }

    private static WavDetails wavDetails(byte[] wav) {
        ByteBuffer data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = 0;
        int channels = 0;
        long audioBytes = 0;
        for (int offset = 12; offset + 8 <= wav.length;) {
            String chunk = new String(wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            long length = Integer.toUnsignedLong(data.getInt(offset + 4));
            int content = offset + 8;
            if (chunk.equals("fmt ") && length >= 16) {
                channels = Short.toUnsignedInt(data.getShort(content + 2));
                sampleRate = data.getInt(content + 4);
            } else if (chunk.equals("data")) {
                audioBytes = length;
            }
            if (length > wav.length - content) break;
            offset = content + (int) length + (int) (length & 1);
        }
        return new WavDetails(channels, sampleRate, audioBytes / (sampleRate * (double) channels * 2));
    }

    private record WavDetails(int channels, int sampleRate, double durationSeconds) {}
}
