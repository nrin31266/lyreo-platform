package com.lyreo.speechassessment.infrastructure;

import com.lyreo.speechassessment.application.AdminPronunciationAttempts.AudioPreparation;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ServiceUnavailableException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class AssessmentAudio implements AudioPreparation {
    static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Override
    public byte[] prepare(byte[] audio, int limitSeconds) {
        int allowedBytes = limitSeconds * BYTES_PER_SECOND + BYTES_PER_SECOND * 3 / 2;
        Path directory = null;
        Process process = null;
        try {
            directory = Files.createTempDirectory("lyreo-pronunciation-");
            Path source = Files.write(directory.resolve("upload"), audio);
            Path target = directory.resolve("decoded");
            process = new ProcessBuilder(
                "ffmpeg", "-nostdin", "-v", "error", "-xerror", "-threads", "1",
                "-protocol_whitelist", "file,pipe",
                "-format_whitelist", "matroska,webm,ogg,mov,mp4,m4a,3gp,3g2,mj2,wav,mp3",
                "-codec_whitelist", "opus,libopus,aac,mp3,mp3float,pcm_u8,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,pcm_f64le",
                "-i", source.toString(), "-map", "0:a:0",
                "-t", Double.toString(limitSeconds + 1.51), "-fs", Integer.toString(allowedBytes + 512),
                "-ar", String.valueOf(SAMPLE_RATE), "-ac", "1",
                "-c:a", "pcm_s16le", "-f", "s16le", target.toString()
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new RequestValidationException("Decoding the recording took too long");
            }
            if (process.exitValue() != 0 || !Files.exists(target) || Files.size(target) == 0) {
                throw new RequestValidationException("Choose a decodable WebM, Ogg, M4A, MP4, WAV or MP3 recording");
            }
            if (Files.size(target) > allowedBytes) {
                throw new RequestValidationException("The recording exceeds the " + limitSeconds + " second limit");
            }
            return wav(Files.readAllBytes(target));
        } catch (IOException failure) {
            throw new ServiceUnavailableException("ASSESSMENT_AUDIO_UNAVAILABLE", "Audio preparation is unavailable", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("ASSESSMENT_AUDIO_UNAVAILABLE", "Audio preparation was interrupted", failure);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (directory != null) cleanup(directory);
        }
    }

    private static byte[] wav(byte[] pcm) {
        return ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46464952).putInt(36 + pcm.length).putInt(0x45564157)
            .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
            .putInt(SAMPLE_RATE).putInt(BYTES_PER_SECOND).putShort((short) 2).putShort((short) 16)
            .putInt(0x61746164).putInt(pcm.length).put(pcm).array();
    }

    private static void cleanup(Path directory) {
        try {
            Files.deleteIfExists(directory.resolve("upload"));
            Files.deleteIfExists(directory.resolve("decoded"));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // Preserve the original audio-processing result.
        }
    }
}
