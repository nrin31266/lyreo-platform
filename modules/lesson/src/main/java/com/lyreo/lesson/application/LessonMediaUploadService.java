package com.lyreo.lesson.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lesson-owned canonical media upload for authoring.
 *
 * <p>This is deliberately not a system-wide MediaService: Lesson is the owner of
 * prepared audio/thumbnail assets. The service never trusts the client MIME or
 * filename, generates safe object keys server-side and stores only canonical
 * object keys; the returned download URL is temporary convenience only.</p>
 */
public final class LessonMediaUploadService {
    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_IMAGE = "IMAGE";

    private static final Map<String, Set<String>> ALLOWED_TYPES = Map.of(
        KIND_AUDIO, Set.of(
            "audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg", "audio/flac", "audio/webm"
        ),
        KIND_IMAGE, Set.of("image/jpeg", "image/png", "image/webp")
    );

    private final ObjectStoragePort storage;
    private final long maxAudioBytes;
    private final long maxImageBytes;
    private final Duration downloadTtl;

    public LessonMediaUploadService(
        ObjectStoragePort storage,
        long maxAudioBytes,
        long maxImageBytes,
        Duration downloadTtl
    ) {
        this.storage = storage;
        this.maxAudioBytes = maxAudioBytes;
        this.maxImageBytes = maxImageBytes;
        this.downloadTtl = downloadTtl;
    }

    public UploadedMedia upload(String kind, byte[] bytes) {
        String normalizedKind = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.containsKey(normalizedKind)) {
            throw new RequestValidationException(
                "Media kind must be AUDIO or IMAGE, got: " + kind
            );
        }
        if (bytes == null || bytes.length == 0) {
            throw new RequestValidationException("Media file is empty");
        }
        long limit = KIND_AUDIO.equals(normalizedKind) ? maxAudioBytes : maxImageBytes;
        if (bytes.length > limit) {
            throw new RequestValidationException(
                normalizedKind + " media exceeds the " + limit + " byte size limit"
            );
        }

        String contentType = MediaTypeSniffer.sniff(bytes).orElseThrow(() ->
            new RequestValidationException(
                "Unsupported or unrecognized media format; expected a supported "
                    + normalizedKind + " type"
            )
        );
        if (!ALLOWED_TYPES.get(normalizedKind).contains(contentType)) {
            throw new RequestValidationException(
                "Media content is " + contentType + ", which is not an allowed "
                    + normalizedKind + " type"
            );
        }

        String extension = extensionOf(contentType);
        String objectKey = "lessons/media/" + normalizedKind.toLowerCase(Locale.ROOT)
            + "/" + UUID.randomUUID() + "." + extension;
        storage.put(objectKey, contentType, bytes);

        String sha256 = sha256(bytes);
        String downloadUrl = storage.createDownloadUrl(objectKey, downloadTtl).toString();
        return new UploadedMedia(objectKey, contentType, bytes.length, sha256, downloadUrl);
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/wav" -> "wav";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            case "audio/webm" -> "webm";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record UploadedMedia(
        String objectKey,
        String contentType,
        long size,
        String sha256,
        String downloadUrl
    ) {}
}
