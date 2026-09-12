package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.application.LessonMediaUploadService;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LessonMediaUploadServiceTest {
    private InMemoryObjectStorage storage;
    private LessonMediaUploadService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryObjectStorage();
        service = new LessonMediaUploadService(storage, 1_000_000, 1_000_000, Duration.ofMinutes(15));
    }

    private static byte[] wavBytes() {
        // RIFF....WAVE header with 44 bytes; content detection only reads the header.
        byte[] bytes = new byte[64];
        bytes[0] = 0x52; bytes[1] = 0x49; bytes[2] = 0x46; bytes[3] = 0x46;
        bytes[8] = 0x57; bytes[9] = 0x41; bytes[10] = 0x56; bytes[11] = 0x45;
        return bytes;
    }

    private static byte[] pngBytes() {
        return new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Test
    void uploadsAudioWithServerGeneratedKeyAndHash() {
        byte[] audio = wavBytes();
        var uploaded = service.upload("AUDIO", audio);

        assertThat(uploaded.objectKey()).startsWith("lessons/media/audio/");
        assertThat(uploaded.contentType()).isEqualTo("audio/wav");
        assertThat(uploaded.size()).isEqualTo(audio.length);
        assertThat(uploaded.sha256()).isEqualTo(sha256(audio));
        assertThat(uploaded.downloadUrl()).contains(uploaded.objectKey());
        assertThat(storage.objects).containsEntry(uploaded.objectKey(), audio);
    }

    @Test
    void uploadsImage() {
        byte[] image = pngBytes();
        var uploaded = service.upload("IMAGE", image);

        assertThat(uploaded.objectKey()).startsWith("lessons/media/image/");
        assertThat(uploaded.contentType()).isEqualTo("image/png");
    }

    @Test
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> service.upload("TEXT", wavBytes()))
            .isInstanceOf(RequestValidationException.class)
            .hasMessageContaining("AUDIO or IMAGE");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> service.upload("AUDIO", new byte[0]))
            .isInstanceOf(RequestValidationException.class)
            .hasMessageContaining("empty");
    }

    @Test
    void rejectsOversizedAudio() {
        LessonMediaUploadService tiny =
            new LessonMediaUploadService(storage, 16, 16, Duration.ofMinutes(1));
        assertThatThrownBy(() -> tiny.upload("AUDIO", wavBytes()))
            .isInstanceOf(RequestValidationException.class)
            .hasMessageContaining("size limit");
    }

    @Test
    void rejectsUnrecognizedContent() {
        assertThatThrownBy(() -> service.upload("AUDIO", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}))
            .isInstanceOf(RequestValidationException.class)
            .hasMessageContaining("Unsupported or unrecognized");
    }

    @Test
    void rejectsImageContentUnderAudioKind() {
        assertThatThrownBy(() -> service.upload("AUDIO", pngBytes()))
            .isInstanceOf(RequestValidationException.class)
            .hasMessageContaining("not an allowed AUDIO type");
    }

    @Test
    void doesNotTrustClientMimeOrFilename() {
        // The service only receives raw bytes; a PNG payload can never become "audio/mp3"
        // just because the client labelled it so.
        assertThatThrownBy(() -> service.upload("AUDIO", pngBytes()))
            .isInstanceOf(RequestValidationException.class);
    }
}
